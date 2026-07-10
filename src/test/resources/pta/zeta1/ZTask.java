/**
 * App-side Runnable implementation — the app part of the open-receiver
 * cone at {@code ZLib.each}'s dispatch (spec Definition II.23). With the
 * callback atom admitted, materialization (spec Definition II.25(d))
 * must make {@code run()} reachable in the client analysis.
 */
public class ZTask implements Runnable {

    static Object SINK;

    @Override
    public void run() {
        SINK = new Object();
    }
}
