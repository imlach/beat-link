package org.deepsymmetry.beatlink.data;
import java.nio.file.*;
import java.util.*;
/** Offline adversarial replay; captures remain local and are deliberately not committed. */
public final class IntegratedAssetsTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Map<String,List<byte[]>> groups=new TreeMap<>();
        try(var files=Files.list(Path.of(args[0]))){for(Path p:files.sorted().toList()){byte[] b=Files.readAllBytes(p);groups.computeIfAbsent(p.getFileName().toString().substring(6),k->new ArrayList<>()).add(b);}}
        int total=0;
        for(var entry:groups.entrySet()){
            var packets=entry.getValue();byte[] first=packets.get(0);var ref=new UdpAssets.Ref(UdpAssets.u32(first,40),first[44]&255,first[45]&255,first[46]&255);
            var assembler=new UdpAssets.Assembler();var wrong=new UdpAssets.Ref(ref.id()+1,ref.source(),ref.slot(),ref.type());
            for(byte[] b:packets)check(assembler.accept(b,wrong)==null,"stale reference accepted");
            var shuffled=new ArrayList<>(packets);Collections.shuffle(shuffled,new Random(42));UdpAssets.Asset result=null;
            for(int i=0;i<shuffled.size();i++){byte[] b=shuffled.get(i);var a=assembler.accept(b,ref);if(a!=null)result=a;if(i<shuffled.size()-1)check(assembler.accept(b,ref)==null,"duplicate completed incomplete asset");}
            check(result!=null,"missing complete asset");
            if(result.kind()==4){byte[] w=UdpAssets.waveform(result);check(w.length/2==(ref.slot()==10?50998:68986),"wave sample count");}
            if(result.kind()==3)check(UdpAssets.waveform(result).length==7200,"preview count");
            if(result.kind()==6)check(UdpAssets.grid(result).beats().length==(ref.slot()==10?778:1005),"grid count");
            if(result.kind()==1){String[] text=UdpAssets.metadata(result);check(text!=null,"metadata unresolved");check(text[0].equals(ref.slot()==10?"Yellow Ochre Pt. 1 (Footshooter Remix)":"Since U Been Gone (Jason Nevins Club)"),"wrong title");System.out.println("Metadata: "+Arrays.toString(text));}
            if(packets.size()>1){assembler=new UdpAssets.Assembler();for(int i=0;i<packets.size()-1;i++)check(assembler.accept(packets.get(i),ref)==null,"missing fragment published");
                assembler.clear();byte[] changed=packets.get(0).clone();changed[changed.length-1]^=1;check(assembler.accept(packets.get(0),ref)==null,"unexpected first fragment");check(assembler.accept(changed,ref)==null,"conflicting duplicate accepted");for(int i=1;i<packets.size();i++)check(assembler.accept(packets.get(i),ref)==null,"conflict left complete assembly");}
            byte[] invalid=first.clone();invalid[0]=0;check(new UdpAssets.Assembler().accept(invalid,ref)==null,"bad magic");
            invalid=first.clone();invalid[50]=(byte)255;invalid[51]=(byte)255;check(new UdpAssets.Assembler().accept(invalid,ref)==null,"unbounded fragment count");
            total++;
        }
        check(total > 0, "no capture groups supplied");
        System.out.println("PASS "+total+" asset groups: shuffled, duplicate, missing, conflicting, stale, malformed; known titles, waveform sizes and grids");
    }
}
