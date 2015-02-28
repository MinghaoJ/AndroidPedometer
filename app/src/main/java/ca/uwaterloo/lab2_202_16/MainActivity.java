package ca.uwaterloo.lab2_202_16;

import ca.uwaterloo.sensortoy.LineGraphView;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.support.v7.app.ActionBarActivity;
import android.support.v4.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class MainActivity extends ActionBarActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container, new SensorDataFragment())
                    .commit();
        }
    }

    public static class SensorDataFragment extends Fragment {

        public SensorDataFragment() {
        }

        LineGraphView graph;
        TextView stepsTextView;
        SensorManager sensorManager;
        Sensor accelerometer;
        SensorEventListener a;
        StepCounter sc;
        ScheduledExecutorService stateMachine = Executors.newSingleThreadScheduledExecutor();

        @Override
        public void onPause() {
            super.onPause();

            sensorManager.unregisterListener(a);
        }

        @Override
        public void onResume() {
            super.onResume();

            sensorManager.registerListener(a, accelerometer,
                    SensorManager.SENSOR_DELAY_UI);
        }

        private TextView makeTextView(View view) {
            TextView tv = new TextView(view.getContext());
            LinearLayout layout = (LinearLayout) view.findViewById(R.id.layout);
            layout.addView(tv);
            return tv;
        }


        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.fragment_main, container, false);
            LinearLayout layout = (LinearLayout) rootView.findViewById(R.id.layout);

            // Implementation of the Clear button to clear step counter and reset the graph.
            Button clearButton = new Button(rootView.getContext());
            clearButton.setText("Save and Clear");
            clearButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    graph.purge();
                    sc.saveClearSteps();
                }
            });
            layout.addView(clearButton);

            graph = new LineGraphView(rootView.getContext(),
                    100,
                    Arrays.asList("x", "y", "z", "sum"));
            layout.addView(graph);
            graph.setVisibility(View.VISIBLE);

            sensorManager = (SensorManager)
                    rootView.getContext().getSystemService(SENSOR_SERVICE);
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);

            stepsTextView = makeTextView(rootView);

            sc = new StepCounter();

            a = new SensorEventListener() {
                final float C = 20;

                public void onAccuracyChanged(Sensor s, int i) {}

                /**
                 * Implementation of a linear acceleration sensor that filters out gravity and a low
                 * pass filter to filter out noise for a smoother graph
                 * @param se
                 */
                public void onSensorChanged(SensorEvent se) {
                    if (se.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                        sc.setAccelX( sc.getAccelX() + (se.values[0] - sc.getAccelX()) / C );
                        sc.setAccelY(sc.getAccelY() + (se.values[1] - sc.getAccelY()) / C);
                        sc.setAccelZ( sc.getAccelZ() + (se.values[2] - sc.getAccelZ()) / C );
                    }
                }
            };

            sensorManager.registerListener(a, accelerometer,
                    SensorManager.SENSOR_DELAY_FASTEST);

            Runnable periodTask = new Runnable() {
                @Override
                public void run() {
                    sc.evaluate();
                    stepsTextView.post(new Runnable() {
                        @Override
                        public void run() {
                            graph.addPoint(new float[] {0,0,0,sc.getAccelSum()});
                            stepsTextView.setText(String.valueOf(sc.prevSteps()));
                        }
                    });
                }
            };
            stateMachine.scheduleAtFixedRate(periodTask,200,5, TimeUnit.MILLISECONDS);

            return rootView;
        }

        /**
         * Implementation of finite state machine with 3 states based off of the graph of the
         * human gait. Utilizes a time delay of 200ms and a peak requirement for counting a step.
         */
        class StepCounter {
            private static final int MAXTIME = 1000;
            private static final double THRESHOLD = 0.9;
            private static final int DELTATIME = 200;

            int steps;
            int prevSteps;
            long prevTime;

            ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_ALARM, 50);
            State currentState;

            private float accelX;
            private float accelY;
            private float accelZ;
            private float accelSum;

            abstract class State {
                abstract public State evaluate();
            }

            /**
             * state1 loops back to itself until the value of dataPoint[3] exceeds THRESHOLD
             */
            State state1 = new State() {
                public State evaluate() {
                    prevTime = System.currentTimeMillis();
                    if (accelSum > THRESHOLD) {
                        return state2;
                    }
                    return state1;
                }
            };

            /**
             * state2 loops back to itself until dataPoint[3] falls below THRESHOLD, then checks the
             * time elapsed since state1. If the time exceeds DELTATIME, it advances to state3.
             * Otherwise, it goes back to state1.
             */
            State state2 = new State() {
                public State evaluate() {
                    if(accelSum < THRESHOLD) {
                        if(System.currentTimeMillis() - prevTime > DELTATIME &&
                                System.currentTimeMillis() - prevTime < MAXTIME) {
                            steps++;
                            toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 50);
                            prevTime = System.currentTimeMillis();
                            return state3;
                        }
                        return state1;
                    }
                    else if(accelSum > 3*THRESHOLD) {
                        return state3;
                    }
                    return state2;
                }
            };

            /**
             * state3 loops back to itself until both dataPoint[3] is below THRESHOLD and the time
             * elapsed is above DELTATIME
             */
           State state3 = new State() {
                public State evaluate() {
                    if(accelSum < THRESHOLD) {
                        if (System.currentTimeMillis() - prevTime > MAXTIME) {
                            return state1;
                        }
                        else if(System.currentTimeMillis() - prevTime > 1.5*DELTATIME) {
                            steps++;
                            toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 50);
                            return state1;
                        }
                    }
                    return state3;
                }
            };

            StepCounter() {
                currentState = state1;
            }

            public void evaluate() {
                // The average of the x, y, and z components, used to
                // analyze accelerometer data on the graph for counting a step
                accelSum = 0.3f * Math.abs(sc.getAccelX()) + 0.3f * Math.abs(sc.getAccelY()) +
                        0.4f * Math.abs(sc.getAccelZ());

                currentState = currentState.evaluate();
            }

            public void setAccelX(float in) { this.accelX = in; }
            public void setAccelY(float in) { this.accelY = in; }
            public void setAccelZ(float in) { this.accelZ = in; }
            public void setAccelSum(float in) { this.accelSum = in; }

            public float getAccelX() { return accelX; }
            public float getAccelY() { return accelY; }
            public float getAccelZ() { return accelZ; }
            public float getAccelSum() { return accelSum; }

            public float[] getAccelArray() {
                return new float[] {accelX, accelY, accelZ, accelSum};
            }

            public int prevSteps() {
                return prevSteps;
            }

            public void saveClearSteps() {
                prevSteps = steps;
                steps = 0;
            }
        }
    }
}