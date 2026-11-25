package local.cc3.manager;

import android.content.Intent;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import java.util.*;

/** App-themed connection instructions; upload server remains explicitly controlled. */
public final class MediaTransferActivity extends Screen {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView address,code,status,detail;
    private Button toggle;
    private ProgressBar progress;
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,750);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);prepareWindow();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        content=column();content.setPadding(dp(28),dp(16),dp(28),dp(20));content.setBackgroundColor(colors.background);scroll.addView(content);
        LinearLayout top=row();top.addView(helpHeading("Laptop uploads",24,
                "Use trusted Wi-Fi or the CC3 hotspot. Transfers use HTTPS. Verify the SHA-256 certificate fingerprint shown here before entering the pairing code in your browser. The pinned laptop uploader verifies it automatically; never bypass a certificate warning without comparing the fingerprint. No mobile data is needed. Uploads stop after 10 minutes idle or two hours total. Keep original copies: files added here survive app updates, but are removed if AVC Manager is uninstalled."),new LinearLayout.LayoutParams(0,-2,1));
        top.addView(button("‹ Entertainment",false,()->finish()));content.addView(top);
        LinearLayout panel=column();panel.setPadding(dp(24),dp(18),dp(24),dp(18));panel.setBackground(shape(colors.surface,16));
        status=text("Uploads are off",19,colors.ink,true);panel.addView(status);
        TextView step=text("1  Connect your laptop to the same Wi-Fi or CC3 hotspot",15,colors.muted,false);step.setPadding(0,dp(14),0,dp(8));panel.addView(step);
        panel.addView(text("2  Open this address in your laptop browser",15,colors.muted,false));
        address=text("Start uploads to show the address",24,colors.accent,true);address.setTextIsSelectable(true);address.setPadding(0,dp(8),0,dp(12));panel.addView(address);
        LinearLayout pair=row();pair.addView(text("3  Enter pairing code  ",15,colors.muted,false));code=text("—",28,colors.ink,true);code.setLetterSpacing(.12f);pair.addView(code);panel.addView(pair);
        detail=text("",14,colors.muted,false);detail.setPadding(0,dp(12),0,dp(4));panel.addView(detail);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(colors.accent));panel.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,-2);gap.topMargin=dp(14);content.addView(panel,gap);
        toggle=button("Start uploads",true,()->{if(MediaTransferService.state().active)startService(new Intent(this,MediaTransferService.class).setAction(MediaTransferService.STOP));else chooseEndpoint();update();});
        LinearLayout actions=row();tile(actions,toggle);content.addView(actions,gap);
        content.addView(button("Verify secure connection",false,this::verifyConnection),gap);
        setContentView(scroll);update();
    }
    private void verifyConnection(){
        PanelDialog panel=new PanelDialog("Verify secure uploads",880);
        panel.message("Compare this SHA-256 fingerprint with your browser's certificate details before trusting the connection. If your browser cannot show it, use tools/cc3-upload.py on the laptop with this fingerprint. A changed IP can produce a new certificate; read the current fingerprint here each time.");
        TextView fingerprint=text(!MediaTransferService.state().active?"Start uploads first":MediaTransferService.fingerprint(),16,colors.accent,true);
        fingerprint.setTextIsSelectable(true);fingerprint.setTypeface(android.graphics.Typeface.MONOSPACE);panel.body.addView(fingerprint);
        panel.action("Close",true,panel::dismiss);panel.show();
    }
    private void chooseEndpoint(){List<MediaTransferService.Endpoint> endpoints=MediaTransferService.endpoints(this);
        if(endpoints.size()<2){start(null);return;}
        PanelDialog panel=new PanelDialog("Choose Wi-Fi address",600);
        for(MediaTransferService.Endpoint endpoint:endpoints)panel.option(endpoint.label(),()->{panel.dismiss();start(endpoint.label());});
        panel.action("Cancel",false,panel::dismiss);panel.show();
    }
    private void start(String address){Intent intent=new Intent(this,MediaTransferService.class).setAction(MediaTransferService.START);if(address!=null)intent.putExtra("address",address);startForegroundService(intent);}
    private void update(){MediaTransferService.State state=MediaTransferService.state();status.setText(state.message);
        address.setText(state.active?state.url:"Start uploads to show the address");code.setText(state.active?state.code:"—");
        boolean starting=state.message.startsWith("Starting");
        toggle.setText(starting?"Starting…":state.active?"Stop uploads":"Start uploads");toggle.setEnabled(!starting);toggle.setSelected(state.active);styleButton(toggle,colors,!state.active,false,state.active);
        detail.setText(state.busy?state.file+" · "+size(state.received)+" / "+size(state.total):state.active?size(state.available)+" available · "+state.completed+" file"+(state.completed==1?"":"s")+" added":"Files stay available for offline playback.");
        progress.setProgress(state.total<=0?0:(int)Math.min(1000,state.received*1000/state.total));progress.setVisibility(state.busy?android.view.View.VISIBLE:android.view.View.GONE);
    }
    static String size(long bytes){return bytes>=1073741824?String.format(Locale.ROOT,"%.1f GiB",bytes/1073741824.0):String.format(Locale.ROOT,"%.1f MiB",bytes/1048576.0);}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
