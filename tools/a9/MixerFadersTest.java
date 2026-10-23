package org.deepsymmetry.beatlink;
import java.nio.file.*;
import java.net.*;
import java.util.*;
/** Offline capture regression and malformed-frame checks for the A9 position decoder. */
public final class MixerFadersTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static DatagramPacket packet(byte[] data)throws Exception{return new DatagramPacket(data,data.length,InetAddress.getByName("169.254.14.1"),50002);}
 public static void main(String[] args)throws Exception {
  int count=0;Set<Integer> ch3=new HashSet<>(),ch4=new HashSet<>();
  try(var files=Files.list(Path.of(args[0]))) {for(Path p:files.sorted().toList()){
   byte[] data=Files.readAllBytes(p);MixerFaders f=MixerFaders.decode(packet(data));check(f!=null,"valid capture rejected");
   check(f.getChannel(1)==0&&f.getChannel(2)==0,"idle channel mapping");ch3.add(f.getChannel(3));ch4.add(f.getChannel(4));check(f.crossfader==-1,"unmapped crossfader presented as known");
   byte[] bad=data.clone();bad[0]=0;check(MixerFaders.decode(packet(bad))==null,"bad magic accepted");
   bad=data.clone();bad[10]=0x58;check(MixerFaders.decode(packet(bad))==null,"VU packet accepted as position");
   bad=data.clone();bad[11]='X';check(MixerFaders.decode(packet(bad))==null,"wrong model accepted");
   check(MixerFaders.decode(packet(Arrays.copyOf(data,247)))==null,"truncated frame accepted");count++;
  }}
  check(ch3.containsAll(Arrays.asList(0,12,79,89,169,195,254,255)),"CH3 motion lost");
  check(ch4.containsAll(Arrays.asList(0,3,119,224,255)),"CH4 motion lost");
  System.out.println("PASS "+count+" A9 frames; channel motion, unknown crossfader, malformed length/magic/type/model.");
 }
}
