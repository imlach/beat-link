package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in experimental UDP asset transport for currently loaded CDJ-3000X streams. */
public final class StreamingAssets {
    private static final StreamingAssets INSTANCE = new StreamingAssets();
    private final Map<DataReference, Entry> entries = new ConcurrentHashMap<>();
    private final ExecutorService requests = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "streaming-asset-request"); t.setDaemon(true); return t;
    });
    private static final class Entry {
        final DataReference reference;
        final InetAddress address;
        final UdpAssets.Ref raw;
        final UdpAssets.Assembler assembler = new UdpAssets.Assembler();
        final Map<Integer, CompletableFuture<UdpAssets.Asset>> futures = new ConcurrentHashMap<>();
        Entry(DataReference r, CdjStatus s) {
            reference=r; address=s.getAddress();
            raw=new UdpAssets.Ref(Integer.toUnsignedLong(r.rekordboxId),r.player,r.slot.protocolValue&255,r.trackType.protocolValue&255);
        }
        void cancel() { for (CompletableFuture<?> f : futures.values()) f.cancel(false); assembler.clear(); }
    }
    private StreamingAssets() {
        VirtualCdj.getInstance().addLifecycleListener(new LifecycleListener() {
            public void started(LifecycleParticipant s) {}
            public void stopped(LifecycleParticipant s) { entries.values().forEach(Entry::cancel); entries.clear(); }
        });
        VirtualCdj.getInstance().addUpdateListener(u -> {
            if (u instanceof CdjStatus) entries.entrySet().removeIf(e -> {
                if (loaded(e.getKey()) != null) return false;
                e.getValue().cancel(); return true;
            });
        });
    }
    public static boolean supports(DataReference r) {
        return Boolean.getBoolean("beatlink.experimentalStreaming") && r != null &&
                (r.slot == CdjStatus.TrackSourceSlot.STREAMING_SLOT || r.slot == CdjStatus.TrackSourceSlot.CLOUD_SLOT);
    }
    private static CdjStatus loaded(DataReference r) {
        VirtualCdj v=VirtualCdj.getInstance(); if(!v.isRunning())return null;
        for(DeviceAnnouncement device:DeviceFinder.getInstance().getCurrentDevices()) {
            if(!"CDJ-3000X".equals(device.getDeviceName()))continue;
            DeviceUpdate u=v.getLatestStatusFor(device);
            if(u instanceof CdjStatus) {CdjStatus s=(CdjStatus)u;
                if(s.getTrackSourcePlayer()==r.player && s.getTrackSourceSlot()==r.slot && s.getTrackType()==r.trackType && s.getRekordboxId()==r.rekordboxId)return s;
            }
        }
        return null;
    }
    public static boolean isCurrent(TrackMetadataUpdate update) {
        if(update.metadata==null)return true;
        TrackMetadata current=MetadataFinder.getInstance().getLatestMetadataFor(update.player);
        if(!supports(update.metadata.trackReference) && (current==null || !supports(current.trackReference)))return true;
        return current!=null && current.trackReference.equals(update.metadata.trackReference) && loaded(current.trackReference)!=null;
    }
    private UdpAssets.Asset get(DataReference r,int kind) {
        if(!supports(r))return null;
        CdjStatus status=loaded(r);if(status==null)return null;
        VirtualCdj.getInstance().udpAssetObserver=this::receive;
        if(entries.size()>=16 && !entries.containsKey(r))return null;
        Entry e=entries.computeIfAbsent(r, key->new Entry(r,status));
        CompletableFuture<UdpAssets.Asset> future=e.futures.computeIfAbsent(kind,key->{
            CompletableFuture<UdpAssets.Asset> f=new CompletableFuture<>();
            requests.submit(()->{
                try {
                    for(int attempt=0;attempt<3&&!f.isDone();attempt++) {
                        CdjStatus current=loaded(r);
                        if(current==null || !current.getAddress().equals(e.address)){f.cancel(false);return;}
                        VirtualCdj.getInstance().requestUdpAsset(e.address,e.raw.id(),e.raw.source(),e.raw.slot(),e.raw.type(),kind);
                        try { f.get(1500,TimeUnit.MILLISECONDS); } catch(TimeoutException ignored) {}
                    }
                    if(!f.isDone())f.complete(null);
                }catch(Exception ex){f.complete(null);}
            });
            return f;
        });
        try { UdpAssets.Asset a=future.get(12,TimeUnit.SECONDS);return loaded(r)==null?null:a; }
        catch(Exception ex){return null;}
    }
    private void receive(DatagramPacket p) {
        for(Entry e:entries.values()) {
            if(!e.address.equals(p.getAddress()) || loaded(e.reference)==null)continue;
            UdpAssets.Asset a=e.assembler.accept(p.getData(),e.raw);
            if(a==null)continue;
            String debug=System.getProperty("beatlink.streamingDebugDir");
            if(debug!=null)try {
                java.nio.file.Path dir=java.nio.file.Paths.get(debug);java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.write(dir.resolve("asset-"+a.ref.id()+"-"+a.kind+"-"+System.nanoTime()+".bin"),a.data);
            }catch(java.io.IOException ignored) {}
            try {
                if(a.kind==3||a.kind==4)UdpAssets.waveform(a);
                if(a.kind==6)UdpAssets.grid(a);
                CompletableFuture<UdpAssets.Asset> f=e.futures.get(a.kind);
                if(f!=null)f.complete(a);
            }catch(IllegalArgumentException ignored) {}
        }
    }
    public static TrackMetadata metadata(DataReference r) {
        UdpAssets.Asset detail=INSTANCE.get(r,4);if(detail==null)return null;
        int duration=(int)(UdpAssets.waveform(detail).length/2/150);
        UdpAssets.Asset text=INSTANCE.get(r,1);String[] labels=text==null?null:UdpAssets.metadata(text);
        CdjStatus status=loaded(r);if(status==null)return null;
        return new TrackMetadata(r,labels==null?"Streaming track (title unresolved)":labels[0],labels==null?"":labels[1],duration,status.getBpm());
    }
    public static WaveformPreview preview(DataReference r) {UdpAssets.Asset a=INSTANCE.get(r,3);return a==null?null:new WaveformPreview(r,ByteBuffer.wrap(UdpAssets.waveform(a)),WaveformStyle.RGB);}
    public static WaveformDetail detail(DataReference r) {UdpAssets.Asset a=INSTANCE.get(r,4);return a==null?null:new WaveformDetail(r,ByteBuffer.wrap(UdpAssets.waveform(a)),WaveformStyle.RGB);}
    public static BeatGrid grid(DataReference r) {UdpAssets.Asset a=INSTANCE.get(r,6);if(a==null)return null;UdpAssets.Grid g=UdpAssets.grid(a);return new BeatGrid(r,g.beats,g.tempos,g.times);}
    public static AlbumArt art(DataReference r) {UdpAssets.Asset a=INSTANCE.get(r,2);return a==null?null:new AlbumArt(r,ByteBuffer.wrap(a.data));}
}
