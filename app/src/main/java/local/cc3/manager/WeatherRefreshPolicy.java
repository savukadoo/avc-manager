package local.cc3.manager;

/** Foreground refresh timing, independent of Android callbacks and provider transport. */
final class WeatherRefreshPolicy {
    static final long INTERVAL=60*60*1000L;
    static long nextDelay(long now,long fetchedAt,boolean retry,int failures){
        if(retry)return Math.min(60000L,5000L << Math.max(0,Math.min(failures,4)));
        return fetchedAt<=0?INTERVAL:Math.max(1000L,INTERVAL-Math.max(0,now-fetchedAt));
    }
    static boolean networkRefresh(boolean active,boolean loading,boolean retry,long fetchedAt,long now){
        return active&&!loading&&(retry||fetchedAt<=0||now-fetchedAt>=INTERVAL);
    }
    private WeatherRefreshPolicy(){}
}
