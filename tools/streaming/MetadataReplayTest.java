package org.deepsymmetry.beatlink.data;
import java.nio.file.*;
import java.util.*;
public class MetadataReplayTest {
 public static void main(String[] args)throws Exception {
  int pass=0,fail=0;
  try(var files=Files.list(Path.of(args[0]))) {for(Path p:files.filter(f->f.getFileName().toString().startsWith("asset-19-1-")).toList()) {
   String[] text=UdpAssets.metadata(new UdpAssets.Asset(new UdpAssets.Ref(19,2,10,8),1,0,Files.readAllBytes(p)));
   if(text!=null&&text[0].equals("Yellow Ochre Pt. 1 (Footshooter Remix)")&&text[1].equals("Vels Trio"))pass++;else{fail++;System.out.println("UNRESOLVED "+p+" "+Arrays.toString(text));}
  }}if(pass==0 || fail!=0)throw new AssertionError("Missing or unresolved metadata fixtures");
  System.out.println("Metadata replay pass="+pass+" unresolved="+fail);
 }
}
