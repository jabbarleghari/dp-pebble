package org.droidplanner.pebble;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.widget.Toast;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.o3dr.android.client.Drone;
import com.o3dr.android.client.ServiceManager;
import com.o3dr.android.client.interfaces.DroneListener;
import com.o3dr.android.client.interfaces.ServiceListener;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.connection.ConnectionParameter;
import com.o3dr.services.android.lib.drone.connection.ConnectionResult;
import com.o3dr.services.android.lib.drone.connection.ConnectionType;
import com.o3dr.services.android.lib.drone.connection.DroneSharePrefs;
import com.o3dr.services.android.lib.drone.connection.StreamRates;
import com.o3dr.services.android.lib.drone.property.Altitude;
import com.o3dr.services.android.lib.drone.property.Battery;
import com.o3dr.services.android.lib.drone.property.GuidedState;
import com.o3dr.services.android.lib.drone.property.Speed;
import com.o3dr.services.android.lib.drone.property.State;
import com.o3dr.services.android.lib.drone.property.VehicleMode;
import com.o3dr.services.android.lib.gcs.follow.FollowState;
import com.o3dr.services.android.lib.gcs.follow.FollowType;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class PebbleCommunicatorService extends Service implements DroneListener, ServiceListener {
    private static final int KEY_MODE = 0;
    private static final int KEY_FOLLOW_TYPE = 1;
    private static final int KEY_TELEM = 2;
    private static final int KEY_APP_VERSION = 3;

    private static final UUID DP_UUID = UUID.fromString("1de866f1-22fa-4add-ba55-e7722167a3b4");
    private static final String EXPECTED_APP_VERSION = "one";

    private Context applicationContext;
    private ServiceManager serviceManager;
    private Drone drone;

    private boolean isForeground = false;
    long timeWhenLastTelemSent = System.currentTimeMillis();
    private PebbleKit.PebbleDataReceiver datahandler;

    //TODO use this eventFilter
    public final static IntentFilter eventFilter = new IntentFilter();
    static {
        eventFilter.addAction(AttributeEvent.STATE_CONNECTED);
        eventFilter.addAction(AttributeEvent.STATE_VEHICLE_MODE);
        eventFilter.addAction(AttributeEvent.BATTERY_UPDATED);
        eventFilter.addAction(AttributeEvent.SPEED_UPDATED);
        eventFilter.addAction(AttributeEvent.FOLLOW_UPDATE);
    }

    //Start the dp-pebble background service
    @Override
    public int onStartCommand(Intent intent, int flags, int startid) {
        applicationContext = getBaseContext();
        datahandler = new PebbleReceiverHandler(DP_UUID);
        PebbleKit.registerReceivedDataHandler(applicationContext, datahandler);
        return START_STICKY;
    }

    @SuppressLint("NewAPI")
    public void connect3DRServices() {
        if(serviceManager == null){
            serviceManager = new ServiceManager(applicationContext);
            serviceManager.connect(this);
        }
        final Handler handler = new Handler();
        if(drone == null){
            drone = new Drone(serviceManager, handler);
            drone.registerDroneListener(this);
        }
        if(!isForeground) {
            final Notification.Builder notificationBuilder = new Notification.Builder(applicationContext).
                    setContentTitle("DP-Pebble Running").
                    setSmallIcon(R.drawable.ic_launcher);
            final Notification notification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
                    ? notificationBuilder.build()
                    : notificationBuilder.getNotification();
            startForeground(1, notification);
            isForeground = true;
        }
    }

    //Runs when 3dr-services is connected.  Immediately connects to drone.
    @Override
    public void onServiceConnected() {
        if (!drone.isStarted()) {
            this.drone.start();
            this.drone.registerDroneListener(this);
        }
        if (!drone.isConnected()) {
            Bundle extraParams = new Bundle();
            extraParams.putInt(ConnectionType.EXTRA_USB_BAUD_RATE, 57600);
            final StreamRates streamRates = new StreamRates(10);
            DroneSharePrefs droneSharePrefs = new DroneSharePrefs("", "", false, false);
            drone.connect(new ConnectionParameter(ConnectionType.TYPE_USB, extraParams, streamRates, droneSharePrefs));
        }
    }

    @Override
    public void onDroneEvent(String event, Bundle bundle) {
        try {
            final String action = new Intent(event).getAction();
            if (AttributeEvent.STATE_DISCONNECTED.equals(action)) {
                onDestroy();
            } else if (AttributeEvent.STATE_CONNECTED.equals(action)) {
                PebbleKit.startAppOnPebble(applicationContext, DP_UUID);
            } else if (AttributeEvent.STATE_VEHICLE_MODE.equals(action)
                    || AttributeEvent.BATTERY_UPDATED.equals(action)
                    || AttributeEvent.SPEED_UPDATED.equals(action)) {
                sendDataToWatchIfTimeHasElapsed(drone);
            } else if ((AttributeEvent.FOLLOW_START.equals(action)
                    || AttributeEvent.FOLLOW_STOP.equals(action))) {
                sendDataToWatchIfTimeHasElapsed(drone);

                FollowState followState = drone.getAttribute(AttributeType.FOLLOW_STATE);
                if (followState != null) {
                    String eventLabel = null;
                    switch (followState.getState()) {
                        case FollowState.STATE_START:
                        case FollowState.STATE_RUNNING:
                            eventLabel = "FollowMe enabled";
                            break;

                        case FollowState.STATE_END:
                            eventLabel = "FollowMe disabled";
                            break;

                        case FollowState.STATE_INVALID:
                            eventLabel = "FollowMe error: invalid state";
                            break;

                        case FollowState.STATE_DRONE_DISCONNECTED:
                            eventLabel = "FollowMe error: drone not connected";
                            break;

                        case FollowState.STATE_DRONE_NOT_ARMED:
                            eventLabel = "FollowMe error: drone not armed";
                            break;
                    }

                    if (eventLabel != null) {
                        Toast.makeText(applicationContext, eventLabel, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }catch(Exception e){
            //TODO figure out what was messing up here
        }
    }

    public void onDestroy() {
        if (drone != null) {
            drone.disconnect();
            drone.unregisterDroneListener(this);
            drone = null;
        }
        if (serviceManager != null) {
            serviceManager.disconnect();
            serviceManager = null;
        }
        if(isForeground){
            stopForeground(true);
            isForeground = false;
        }
    }

    private double roundToOneDecimal(double value) {
        return (double) Math.round(value * 10) / 10;
    }

    /**
     * Calls sendDataToWatchNow if and only if the timeout of 500ms has elapsed
     * since last call to prevent DOSing the pebble. If not, the packet will be
     * dropped. If this packet is important (e.g. mode change), call
     * sendDataToWatchNow directly.
     *
     * @param drone
     */
    public void sendDataToWatchIfTimeHasElapsed(Drone drone) {
        if (System.currentTimeMillis() - timeWhenLastTelemSent > 500) {
            sendDataToWatchNow(drone);
            timeWhenLastTelemSent = System.currentTimeMillis();
        }
    }

    /**
     * Sends a full dictionary with updated information when called. If no
     * pebble is present, the watchapp isn't installed, or the watchapp isn't
     * running, nothing will happen.
     *
     * @param drone
     */
    public void sendDataToWatchNow(Drone drone) {
        final FollowState followState = drone.getAttribute(AttributeType.FOLLOW_STATE);
        final State droneState = drone.getAttribute(AttributeType.STATE);
        if (followState == null || droneState == null)
            return;

        PebbleDictionary data = new PebbleDictionary();

        VehicleMode mode = droneState.getVehicleMode();
        if (mode == null)
            return;

        final GuidedState guidedState = drone.getAttribute(AttributeType.GUIDED_STATE);
        String modeLabel = mode.getLabel();
        if (!droneState.isArmed())
            modeLabel = "Disarmed";
        else if (followState.isEnabled())
            modeLabel = "Follow";
        else if (guidedState.isIdle())
            modeLabel = "Paused";

        data.addString(KEY_MODE, modeLabel);

        FollowType type = followState.getMode();
        if (type != null) {
            data.addString(KEY_FOLLOW_TYPE, type.getTypeLabel());
        } else
            data.addString(KEY_FOLLOW_TYPE, "none");

        final Battery droneBattery = drone.getAttribute(AttributeType.BATTERY);
        Double battVoltage = droneBattery.getBatteryVoltage();
        if (battVoltage != null)
            battVoltage = 0.0;
        String bat = "Bat:" + Double.toString(roundToOneDecimal(battVoltage)) + "V";

        final Speed droneSpeed = drone.getAttribute(AttributeType.SPEED);
        String speed = "Speed: " + Double.toString(roundToOneDecimal(droneSpeed.getAirSpeed()));

        final Altitude droneAltitude = drone.getAttribute(AttributeType.ALTITUDE);
        String altitude = "Alt: " + Double.toString(roundToOneDecimal(droneAltitude.getAltitude()));
        String telem = bat + "\n" + altitude + "\n" + speed;
        data.addString(KEY_TELEM, telem);

        data.addString(KEY_APP_VERSION, EXPECTED_APP_VERSION);

        PebbleKit.sendDataToPebble(applicationContext, DP_UUID, data);
    }

    public class PebbleReceiverHandler extends PebbleKit.PebbleDataReceiver {

        private static final int KEY_PEBBLE_REQUEST = 100;
        private static final int KEY_REQUEST_MODE_FOLLOW = 101;
        private static final int KEY_REQUEST_CYCLE_FOLLOW_TYPE = 102;
        private static final int KEY_REQUEST_PAUSE = 103;
        private static final int KEY_REQUEST_MODE_RTL = 104;
        private static final int KEY_REQUEST_CONNECT = 105;
        private static final int KEY_REQUEST_DISCONNECT = 106;

        protected PebbleReceiverHandler(UUID id) {
            super(id);
        }

        @Override
        public void receiveData(Context context, int transactionId, PebbleDictionary data) {
            PebbleKit.sendAckToPebble(applicationContext, transactionId);
            //connect if not connected yet
            connect3DRServices();
            if (drone == null || !drone.isConnected())
                return;
            FollowState followMe = drone.getAttribute(AttributeType.FOLLOW_STATE);

            int request = (data.getInteger(KEY_PEBBLE_REQUEST).intValue());
            switch (request) {

                case KEY_REQUEST_CONNECT:
                    //Not needed. Any KEY_REQUEST_... should attempt connect.  See above.
                    break;

                case KEY_REQUEST_DISCONNECT:
                    onDestroy();
                    break;

                case KEY_REQUEST_MODE_FOLLOW:
                    if (followMe.isEnabled()) {
                        drone.disableFollowMe();
                    } else {
                        drone.enableFollowMe(followMe.getMode());
                    }
                    break;

                case KEY_REQUEST_CYCLE_FOLLOW_TYPE:
                    List<FollowType> followTypes = Arrays.asList(FollowType.values());
                    int currentTypeIndex = followTypes.indexOf(followMe.getMode());
                    int nextTypeIndex = currentTypeIndex++ % followTypes.size();
                    drone.enableFollowMe(followTypes.get(nextTypeIndex));
                    break;

                case KEY_REQUEST_PAUSE:
                    drone.pauseAtCurrentLocation();
                    break;

                case KEY_REQUEST_MODE_RTL:
                    drone.changeVehicleMode(VehicleMode.COPTER_RTL);
                    break;
            }
        }
    }

    @Override
    public void onServiceInterrupted() {

    }

    @Override
    public void onDroneConnectionFailed(ConnectionResult connectionResult) {

    }

    @Override
    public void onDroneServiceInterrupted(String s) {
        drone.destroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}