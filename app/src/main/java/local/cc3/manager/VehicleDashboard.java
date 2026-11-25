package local.cc3.manager;

import android.view.Gravity;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Dashboard composition only; connection, selection and persistence belong to their controllers. */
final class VehicleDashboard {
    static List<ObdGaugeView> build(MainActivity activity,LinearLayout parent,int count,IntConsumer open){
        List<ObdGaugeView> gauges=new ArrayList<>();
        int columns=count==4?2:3,rows=count==9?3:2;
        for(int row=0;row<rows;row++){
            LinearLayout line=activity.row();line.setGravity(Gravity.FILL);
            LinearLayout.LayoutParams rowBounds=new LinearLayout.LayoutParams(-1,0,1);
            if(row>0)rowBounds.topMargin=activity.dp(10);
            parent.addView(line,rowBounds);
            for(int column=0;column<columns;column++){
                final int slot=row*columns+column;
                ObdGaugeView gauge=new ObdGaugeView(activity);gauge.setOnClickListener(v->open.accept(slot));
                LinearLayout.LayoutParams bounds=new LinearLayout.LayoutParams(0,-1,1);
                if(column>0)bounds.leftMargin=activity.dp(10);
                line.addView(gauge,bounds);gauges.add(gauge);
            }
        }
        return gauges;
    }
    private VehicleDashboard(){ }
}
