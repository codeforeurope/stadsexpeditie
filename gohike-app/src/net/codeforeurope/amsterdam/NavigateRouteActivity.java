package net.codeforeurope.amsterdam;

import java.util.ArrayList;

import net.codeforeurope.amsterdam.dialog.CheckinDialogFragment;
import net.codeforeurope.amsterdam.dialog.TargetHintDialogFragment;
import net.codeforeurope.amsterdam.model.Waypoint;
import net.codeforeurope.amsterdam.util.ApiConstants;
import net.codeforeurope.amsterdam.util.DataConstants;
import android.app.ActionBar;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.location.LocationListener;

public class NavigateRouteActivity extends AbstractGameActivity implements SensorEventListener, LocationListener {
	private static final LayoutParams PROGRESS_IMAGE_LAYOUT_PARAMS = new ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

	private static final int ANIMATION_DURATION = 300;

	private static final int COMPASS_UPDATE_THRESHOLD = 500;

	private static final int CHECKIN_DISTANCE = 7000; // Change to 20 for

	private static final int SHOW_LOCATION_DETAIL = 1234;
	// production

	CheckinDialogFragment checkinDialog;

	TargetHintDialogFragment targetHintDialog;

	SensorManager sensorManager;

	ImageView compassRose;

	ImageView compassTarget;

	TextView distanceText;

	float targetBearing = 0;

	private Sensor accelerometer;

	private Sensor magnetometer;

	LocationManager locationManager;

	public boolean checkinInProgress = false;

	private TextView targetName;

	private LinearLayout progressView;

	float[] mGravity;
	float[] mGeomagnetic;
	long timestamp = 0;
	float azimuth = 0;

	// private BroadcastReceiver checkinsReceiver;

	@Override
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		checkinDialog = new CheckinDialogFragment();
		targetHintDialog = new TargetHintDialogFragment();
		setUpViewReferences();
		setupSensorReferences();

		setupBroadcastReceiver();

	}

	@Override
	protected void onRestart() {
		super.onRestart();
		setupBroadcastReceiver();

	}

	private void setupBroadcastReceiver() {

	}

	private void setupSensorReferences() {
		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

		locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
	}

	private void setupActionBar() {
		ActionBar actionBar = getActionBar();
		actionBar.setDisplayHomeAsUpEnabled(true);
		actionBar.setTitle(getCurrentRoute().name.getLocalizedValue());
	}

	private void loadData() {
		if (isRouteFinished()) {
			checkinInProgress = false;
			Intent intent = new Intent(this, RewardActivity.class);
			startActivity(intent);
			finish();
			overridePendingTransition(R.anim.enter_from_right, R.anim.leave_to_left);
		} else {
			Waypoint currentTarget = getCurrentTarget();
			targetName.setText(currentTarget.name.getLocalizedValue());
			distanceText.setText("...");
		}
	}

	private Waypoint getCurrentTarget() {
		Waypoint target = getIntent().getParcelableExtra(DataConstants.WAYPOINT);
		if (target == null) {
			target = getDefaultTarget();
		}
		return target;
	}

	private Waypoint getDefaultTarget() {
		for (Waypoint waypoint : getCurrentRoute().waypoints) {
			if (!isWaypointCheckedIn(waypoint)) {
				return waypoint;
			}
		}
		return null;
	}

	private void setUpViewReferences() {
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		setContentView(R.layout.navigate);
		compassRose = (ImageView) findViewById(R.id.navigate_rose);
		compassTarget = (ImageView) findViewById(R.id.navigate_target);
		distanceText = (TextView) findViewById(R.id.navigate_overlay_target_distance);
		targetName = (TextView) findViewById(R.id.navigate_overlay_target_text);
		progressView = (LinearLayout) findViewById(R.id.navigate_progress);
	}

	@Override
	public void onBackPressed() {
		super.onBackPressed();
		overridePendingTransition(R.anim.enter_from_left, R.anim.leave_to_right);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			// app icon in action bar clicked; go home
			onBackPressed();
			return true;
		case R.id.menu_show_map:
			Intent i = new Intent(getBaseContext(), OrientationMapActivity.class);
			startActivity(i);
			overridePendingTransition(R.anim.enter_from_right, R.anim.leave_to_left);
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.route_navigate, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public void onResume() {
		super.onResume();
		sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
		sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
		locationClient.connect();
		loadData();
		setupActionBar();
		updateProgress();
		// if (checkinInProgress) {
		// showLocationDetail();
		// // showTargetHintDialog();
		// }
	}

	@Override
	public void onPause() {
		super.onPause();
		sensorManager.unregisterListener(this);
		if (locationClient.isConnected()) {
			locationClient.removeLocationUpdates(this);
		}
		locationClient.disconnect();
	}

	public void onSensorChanged(SensorEvent event) {

		if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
			mGravity = event.values;
		if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
			mGeomagnetic = event.values;

		if (mGravity != null && mGeomagnetic != null) {

			float R[] = new float[9];
			float I[] = new float[9];
			boolean success = SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic);

			long difference = (event.timestamp - timestamp) / 1000000;

			if (success && event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD
					&& difference > COMPASS_UPDATE_THRESHOLD) {
				timestamp = event.timestamp;
				float orientation[] = new float[3];
				SensorManager.getOrientation(R, orientation);
				azimuth = -1 * (float) Math.toDegrees(orientation[0]);
				compassRose.animate().setDuration(ANIMATION_DURATION).rotation(360 + azimuth);
				compassTarget.animate().setDuration(ANIMATION_DURATION).rotation(360 + azimuth + targetBearing);

			}
		}

	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onLocationChanged(Location location) {
		if (!checkinInProgress && !isRouteFinished()) {
			Waypoint currentTarget = getCurrentTarget();
			final float[] results = new float[3];
			Location.distanceBetween(location.getLatitude(), location.getLongitude(), currentTarget.latitude,
					currentTarget.longitude, results);
			updateDistance(results[0]);
			targetBearing = results[2];
			if (results[0] < CHECKIN_DISTANCE) {
				Bundle dialogArgs = new Bundle();
				dialogArgs.putParcelable(ApiConstants.CURRENT_TARGET, currentTarget);
				checkinDialog.setArguments(dialogArgs);
				checkinDialog.show(getSupportFragmentManager(), "checkin");
				checkinInProgress = true;
			}
		}

	}

	private void updateDistance(float rawDistance) {
		String distance;
		if (rawDistance > 1000) {
			distance = getString(R.string.target_distance_km, rawDistance / 1000);
		} else {
			distance = getString(R.string.target_distance_m, rawDistance);
		}

		distanceText.setText(distance);

	}

	public void doCheckin() {
		// gameStateService.checkin();
	}

	public void doDismissTargetHint() {

		// set that the check-in process has finished
		checkinInProgress = false;

	}

	private void showLocationDetail() {
		Intent intent = new Intent(getBaseContext(), LocationDetailActivity.class);
		// intent.putExtra(ApiConstants.CURRENT_WAYPOINT,
		// gameStateService.getPreviousTarget());
		intent.putExtra(ApiConstants.JUST_FOUND, true);
		startActivityForResult(intent, SHOW_LOCATION_DETAIL);
		overridePendingTransition(R.anim.enter_from_right, R.anim.leave_to_left);

	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == SHOW_LOCATION_DETAIL) {
			showTargetHintDialog();
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	private void updateProgress() {

		ArrayList<Waypoint> waypoints = getCurrentRoute().waypoints;
		int numberOfWaypoints = waypoints.size();
		if (progressView.getChildCount() != numberOfWaypoints) {
			progressView.removeAllViews();
		}
		for (int i = 0; i < numberOfWaypoints; i++) {
			Waypoint waypoint = waypoints.get(i);
			ImageView progressImage = (ImageView) progressView.getChildAt(i);
			if (progressImage == null) {
				progressImage = new ImageView(getBaseContext());
				progressImage.setLayoutParams(PROGRESS_IMAGE_LAYOUT_PARAMS);
				progressView.addView(progressImage);
			}
			if (isWaypointCheckedIn(waypoint)) {
				progressImage.setImageResource(R.drawable.progress_check);
			} else {
				progressImage.setImageResource(R.drawable.progress_target);
			}

		}

	}

	private void showTargetHintDialog() {
		// Bundle dialogArgs = new Bundle();
		// dialogArgs.putParcelable(ApiConstants.CURRENT_TARGET,
		// gameStateService.getCurrentTarget());
		// targetHintDialog.setArguments(dialogArgs);
		// targetHintDialog.show(getFragmentManager(), "found");
		// checkinInProgress = true;
	}

	@Override
	public void onConnected(Bundle bundle) {
		locationClient.requestLocationUpdates(locationRequest, this);

	}
}
