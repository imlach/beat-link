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
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(StreamingAssets.class);
    private final ScheduledExecutorService requests = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "streaming-asset-request"); t.setDaemon(true); return t;
    });
    private static final class Entry {
        final DataReference reference;
        final InetAddress address;
        final UdpAssets.Ref raw;
        final UdpAssets.Assembler assembler = new UdpAssets.Assembler();
        final Map<Integer, StreamingRequest<UdpAssets.Asset>> futures = new ConcurrentHashMap<>();
        Entry(DataReference r, CdjStatus s) {
            reference=r; address=s.getAddress();
            raw=new UdpAssets.Ref(Integer.toUnsignedLong(r.rekordboxId),r.player,r.slot.protocolValue&255,r.trackType.protocolValue&255);
        }
        void cancel() { for (StreamingRequest<?> f : futures.values()) f.cancel(); assembler.clear(); }
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
        StreamingRequest<UdpAssets.Asset> request=e.futures.computeIfAbsent(kind,key ->
            new StreamingRequest<>(requests, () -> {
                CdjStatus current=loaded(r);
                return entries.get(r)==e && current!=null && current.getAddress().equals(e.address);
            }, attempt -> {
                logger.debug("Streaming asset request ref={} kind={} destination={} attempt={}", r,kind,e.address,attempt);
                VirtualCdj.getInstance().requestUdpAsset(e.address,e.raw.id(),e.raw.source(),e.raw.slot(),e.raw.type(),kind);
            }, (attempt,delay,failure) -> {
                // First three requests retain the quick initial cadence; subsequent attempts back off.
                if(failure!=null) logger.warn("Streaming asset send failed ref={} kind={} attempt={} retryMs={}",r,kind,attempt,delay,failure);
                else if(attempt>=3) logger.info("Streaming asset still pending ref={} kind={} attempt={} retryMs={}",r,kind,attempt,delay);
            }, attempt -> StreamingRequest.delay((int)attempt)));
        request.start(); // Only after publication, so immediate responses find the request.
        try { UdpAssets.Asset a=request.future.get(12,TimeUnit.SECONDS);return entries.get(r)!=e || loaded(r)==null?null:a; }
        catch(InterruptedException ex){Thread.currentThread().interrupt();return null;}
        catch(TimeoutException ex){logger.debug("Streaming asset caller wait expired; background retry continues ref={} kind={}",r,kind);return null;}
        catch(CancellationException ex){return null;}
        catch(ExecutionException ex){logger.warn("Streaming asset request failed ref={} kind={}",r,kind,ex);e.futures.remove(kind,request);return null;}
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
            }catch(java.io.IOException ex) {logger.warn("Unable to write streaming diagnostic asset ref={} kind={}",e.reference,a.kind,ex);}
            try {
                if(a.kind==3||a.kind==4)UdpAssets.waveform(a);
                if(a.kind==6)UdpAssets.grid(a);
                StreamingRequest<UdpAssets.Asset> f=e.futures.get(a.kind);
                if(f!=null && f.complete(a)) {
                    logger.info("Streaming asset received ref={} kind={} bytes={}",e.reference,a.kind,a.data.length);
                    requests.execute(() -> {
                        try {
                            if(entries.get(e.reference)==e && loaded(e.reference)!=null)
                                MetadataFinder.getInstance().streamingAssetAvailable(e.reference,a.kind);
                        } catch(Exception ex) {logger.warn("Unable to publish late streaming asset ref={} kind={}",e.reference,a.kind,ex);}
                    });
                }
            }catch(IllegalArgumentException ex) {logger.warn("Invalid streaming asset ref={} kind={} bytes={}; retry remains active",e.reference,a.kind,a.data.length,ex);}
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
