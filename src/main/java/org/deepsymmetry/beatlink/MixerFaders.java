package org.deepsymmetry.beatlink;

import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Experimental, read-only A9 channel positions; raw values are not audio gain in dB. */
public final class MixerFaders {
    private static final AtomicReference<MixerFaders> latest = new AtomicReference<>();
    public final long timestamp = System.nanoTime();
    public final String address;
    private final int[] channels;
    public final int crossfader;
    private MixerFaders(DatagramPacket p) {
        byte[] b=p.getData();address=p.getAddress().getHostAddress();channels=new int[4];
        for(int i=0;i<4;i++)channels[i]=b[36+24*i+11]&255;
        crossfader=p.getLength()==266?b[180]&255:-1; // Not mapped in the observed 248-byte variant.
    }
    public int getChannel(int channel) {if(channel<1||channel>4)throw new IllegalArgumentException("Channel 1..4");return channels[channel-1];}
    public static MixerFaders getLatest() {MixerFaders v=latest.get();return v!=null&&System.nanoTime()-v.timestamp<2_000_000_000L?v:null;}
    static MixerFaders decode(DatagramPacket p) {
        if(p.getOffset()!=0 || (p.getLength()!=266 && p.getLength()!=248))return null;
        byte[] b=p.getData();
        if(!Arrays.equals(Arrays.copyOf(b,10),"Qspt1WmJOL".getBytes(java.nio.charset.StandardCharsets.US_ASCII))||b[10]!=0x39)return null;
        if(!new String(b,11,6,java.nio.charset.StandardCharsets.US_ASCII).equals("DJM-A9"))return null;
        return new MixerFaders(p);
    }
    static void accept(DatagramPacket p) {
        MixerFaders decoded=decode(p);if(decoded==null)return;
        DeviceAnnouncement device=DeviceFinder.getInstance().getCurrentDevices().stream().filter(d->d.getAddress().equals(p.getAddress())&&d.getDeviceName().equals("DJM-A9")).findFirst().orElse(null);
        if(device!=null)latest.set(decoded);
    }
}
