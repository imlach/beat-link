package org.deepsymmetry.beatlink.data;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.io.*;

/** Experimental CDJ-3000X UDP assets, derived from Ross's deck captures, not vendor code. */
public final class UdpAssets {
    public static final class Ref { final long id; final int source, slot, type; public Ref(long id,int source,int slot,int type){this.id=id;this.source=source;this.slot=slot;this.type=type;} public long id(){return id;} public int source(){return source;} public int slot(){return slot;} public int type(){return type;} public boolean equals(Object o){if(!(o instanceof Ref))return false;Ref r=(Ref)o;return id==r.id&&source==r.source&&slot==r.slot&&type==r.type;} public int hashCode(){return Objects.hash(id,source,slot,type);} public String toString(){return id+":"+source+":"+slot+":"+type;} }
    public static final class Asset { final Ref ref; final int kind,variant; final byte[] data; Asset(Ref ref,int kind,int variant,byte[] data){this.ref=ref;this.kind=kind;this.variant=variant;this.data=data;} public Ref ref(){return ref;} public int kind(){return kind;} public int variant(){return variant;} public byte[] data(){return data;} }
    private static final byte[] MAGIC="Qspt1WmJOL".getBytes(StandardCharsets.US_ASCII);
    static int u16(byte[] b,int at) {return ByteBuffer.wrap(b).getShort(at)&65535;}
    static long u32(byte[] b,int at) {return ByteBuffer.wrap(b).getInt(at)&0xffffffffL;}
    static class Parts { final long created=System.nanoTime(); final byte[][] parts; int bytes; Parts(int n){parts=new byte[n][];} }
    public static class Assembler {
        private final Map<String,Parts> pending=new HashMap<>();
        public synchronized Asset accept(byte[] b,Ref expected) {
            pending.values().removeIf(p -> System.nanoTime()-p.created>10_000_000_000L);
            if(b.length<52 || b[10]!=0x56 || !Arrays.equals(MAGIC,Arrays.copyOf(b,10)))return null;
            Ref ref=new Ref(u32(b,40),b[44]&255,b[45]&255,b[46]&255);
            if(!ref.equals(expected))return null;
            int index=u16(b,48), count=u16(b,50),kind=b[37]&255;
            if(count<1 || count>2048 || index>=count || b.length>2048 || !Set.of(1,2,3,4,6,8,9).contains(kind))return null;
            String key=ref+":"+u32(b,36);
            if(!pending.containsKey(key) && pending.size()>=16)return null;
            Parts p=pending.computeIfAbsent(key,k->new Parts(count));
            byte[] payload=Arrays.copyOfRange(b,52,b.length);
            if(p.parts.length!=count || (p.parts[index]!=null && !Arrays.equals(p.parts[index],payload))) {pending.remove(key);return null;}
            if(p.parts[index]==null){p.parts[index]=payload;p.bytes+=payload.length;}
            if(p.bytes>2_000_000){pending.remove(key);return null;}
            for(byte[] part:p.parts)if(part==null)return null;
            ByteArrayOutputStream out=new ByteArrayOutputStream(p.bytes);
            for(byte[] part:p.parts)out.write(part,0,part.length);
            pending.remove(key);
            return new Asset(ref,kind,u16(b,38),out.toByteArray());
        }
        public synchronized void clear(){pending.clear();}
    }
    public static byte[] waveform(Asset a) {
        byte[] b=a.data; int header=a.kind==4?16:12, width=a.kind==4?2:6;
        if(a.variant!=256 || b.length<header || u16(b,8)!=width || u32(b,0)*width!=u32(b,4) || u32(b,4)!=b.length-header || (a.kind==4&&u16(b,10)!=150))throw new IllegalArgumentException("Unsupported waveform header");
        return Arrays.copyOfRange(b,header,b.length);
    }
    public static final class Grid { final int[] beats,tempos; final long[] times; Grid(int[] beats,int[] tempos,long[] times){this.beats=beats;this.tempos=tempos;this.times=times;} public int[] beats(){return beats;} public int[] tempos(){return tempos;} public long[] times(){return times;} }
    public static Grid grid(Asset a) {
        byte[] b=a.data;
        if(b.length<8||u16(b,0)!=8||u32(b,4)*8!=b.length-8)throw new IllegalArgumentException("Invalid beat grid");
        int n=(b.length-8)/8;int[] beats=new int[n],tempos=new int[n];long[] times=new long[n];
        for(int i=0;i<n;i++){int at=8+i*8;beats[i]=u16(b,at);tempos[i]=u16(b,at+2);times[i]=u32(b,at+4);if(beats[i]<1||beats[i]>4||tempos[i]==0||(i>0&&times[i]<times[i-1]))throw new IllegalArgumentException("Invalid beat entry");}
        return new Grid(beats,tempos,times);
    }
    /** Conservative ASCII-only hypothesis decoder. Unknown/Unicode encodings remain visibly unresolved. */
    public static String[] metadata(Asset a) {
        if(a.data.length!=536)return null;
        int[][] freq=new int[2][256]; for(int i=0;i<512;i++)freq[i&1][a.data[i]&255]++;
        int[] keys=new int[2];for(int parity=0;parity<2;parity++)for(int i=1;i<256;i++)if(freq[parity][i]>freq[parity][keys[parity]])keys[parity]=i;
        Map<String,Integer> candidates=new HashMap<>();
        for(int rot=0;rot<8;rot++) for(boolean le:new boolean[]{true,false}) {
            byte[] decoded=new byte[512];for(int i=0;i<512;i++){int x=(a.data[i]&255)^keys[i&1];decoded[i]=(byte)((x>>>rot)|(x<<(8-rot)));}
            Charset charset=le?StandardCharsets.UTF_16LE:StandardCharsets.UTF_16BE;
            String title=new String(decoded,0,256,charset).split("\u0000",-1)[0],artist=new String(decoded,256,256,charset).split("\u0000",-1)[0];
            String both=title+"\n"+artist;int score=0;boolean valid=title.length()>=3&&artist.length()>=2;
            for(char c:(title+artist).toCharArray()){if(c<32||c>126)valid=false;score+=Character.isLetterOrDigit(c)?2:c==' '?1:0;}
            if(valid)candidates.merge(both,score,Math::max);
        }
        var sorted=new ArrayList<>(candidates.entrySet());sorted.sort((a1,b1)->Integer.compare(b1.getValue(),a1.getValue()));
        if(sorted.isEmpty()||(sorted.size()>1&&sorted.get(0).getValue()-sorted.get(1).getValue()<10))return null;
        return sorted.get(0).getKey().split("\n",-1);
    }
}
