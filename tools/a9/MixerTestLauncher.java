import java.awt.*;
import javax.swing.*;
import org.deepsymmetry.beatlink.*;

/** Adds a read-only A9 fader window to the isolated native BLT integration. */
public final class MixerTestLauncher {
    private static void showMixer() {
        JFrame frame=new JFrame("A9 channel faders — experimental");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        JPanel channels=new JPanel(new GridLayout(1,4,16,0));
        JProgressBar[] bars=new JProgressBar[4];JLabel[] labels=new JLabel[4];
        for(int i=0;i<4;i++){
            JPanel channel=new JPanel(new BorderLayout(0,12));
            channel.add(new JLabel("Channel "+(i+1),SwingConstants.CENTER),BorderLayout.NORTH);
            bars[i]=new JProgressBar(SwingConstants.VERTICAL,0,255);bars[i].setPreferredSize(new Dimension(60,210));
            channel.add(bars[i]);labels[i]=new JLabel("Unavailable",SwingConstants.CENTER);channel.add(labels[i],BorderLayout.SOUTH);channels.add(channel);
        }
        channels.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        JLabel state=new JLabel("Waiting for A9 fader data",SwingConstants.CENTER);
        frame.add(channels);frame.add(state,BorderLayout.SOUTH);frame.pack();frame.setLocationByPlatform(true);frame.setVisible(true);
        new Timer(100,e->{MixerFaders f=VirtualCdj.getInstance().isRunning()?MixerFaders.getLatest():null;
            for(int i=0;i<4;i++){bars[i].setEnabled(f!=null);bars[i].setValue(f==null?0:f.getChannel(i+1));labels[i].setText(f==null?"Unavailable":String.format("%.1f%%",100.0*f.getChannel(i+1)/255));bars[i].setToolTipText(f==null?"No current reading":"Raw position: "+f.getChannel(i+1)+" / 255");}
            state.setText(f==null?"No current mixer data":"Live fader positions • read-only");
        }).start();
    }
    public static void main(String[] args)throws Exception {
        Thread monitor=new Thread(()->{
            boolean joined=false,shown=false;long logged=0;
            while(true)try {
                VirtualCdj v=VirtualCdj.getInstance();
                if(!v.isRunning())joined=false;
                else {
                    if(!joined){Thread.sleep(10000);if(!v.isRunning())continue;v.beginMixerMonitor();joined=true;}
                    MixerFaders f=MixerFaders.getLatest();
                    if(f!=null&&!shown){shown=true;SwingUtilities.invokeLater(MixerTestLauncher::showMixer);}
                    if(f!=null&&System.nanoTime()-logged>2_000_000_000L){logged=System.nanoTime();System.out.println("A9 positions: "+f.getChannel(1)+","+f.getChannel(2)+","+f.getChannel(3)+","+f.getChannel(4));}
                }
                Thread.sleep(1000);
            }catch(Exception e){System.err.println("Mixer monitor: "+e);try{Thread.sleep(5000);}catch(InterruptedException ignored){}}
        },"a9-monitor-registration");monitor.setDaemon(true);monitor.start();
        TestLauncher.main(args);
    }
}
