package local.cc3.manager;

public final class WeatherRefreshPolicyTest {
    public static void main(String[] args){
        long now=10_000_000L;
        assert WeatherRefreshPolicy.nextDelay(now,now,false,0)==3_600_000;
        assert WeatherRefreshPolicy.nextDelay(now,now-1_800_000,false,0)==1_800_000;
        assert WeatherRefreshPolicy.nextDelay(now,now-4_000_000,false,0)==1000;
        assert WeatherRefreshPolicy.nextDelay(now,now+10000,false,0)==3_600_000;
        assert WeatherRefreshPolicy.nextDelay(now,0,true,0)==5000;
        assert WeatherRefreshPolicy.nextDelay(now,0,true,2)==20000;
        assert WeatherRefreshPolicy.nextDelay(now,0,true,100)==60000;
        assert WeatherRefreshPolicy.networkRefresh(true,false,true,now,now);
        assert !WeatherRefreshPolicy.networkRefresh(false,false,true,0,now);
        assert !WeatherRefreshPolicy.networkRefresh(true,true,true,0,now);
        assert !WeatherRefreshPolicy.networkRefresh(true,false,false,now-1000,now);
        assert WeatherRefreshPolicy.networkRefresh(true,false,false,now-3_600_000,now);
        System.out.println("12 weather refresh timing checks passed");
    }
}
