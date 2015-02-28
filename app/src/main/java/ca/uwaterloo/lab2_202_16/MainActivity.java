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

        AccelerometerEventListener a;

        StepCounter stepCounter;

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
                    stepCounter.clearSteps();
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

            stepCounter = new StepCounter();

            a = new AccelerometerEventListener(graph, stepCounter, stepsTextView);

            sensorManager.registerListener(a, accelerometer,
                    SensorManager.SENSOR_DELAY_FASTEST);

            return rootView;
        }

        class AccelerometerEventListener implements SensorEventListener {
            LineGraphView graph;
            StepCounter stepCounter;
            TextView stepsTextView;
            float[] smoothedPoint = new float[] {0,0,0,0};
            final float C = 10;

            public AccelerometerEventListener(LineGraphView graph, StepCounter stepCounter,
                                              TextView stepsTextView){
                this.graph = graph;
                this.stepCounter = stepCounter;
                this.stepsTextView = stepsTextView;
            }


            public void onAccuracyChanged(Sensor s, int i) {}

            /**
             * Implementation of a linear acceleration sensor that filters out gravity and a low
             * pass filter to filter out noise for a smoother graph
             * @param se
             */
            public void onSensorChanged(SensorEvent se) {
                if (se.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                        smoothedPoint[0] += (se.values[0] - smoothedPoint[0]) / C;
                        smoothedPoint[1] += (se.values[1] - smoothedPoint[1]) / C;
                        smoothedPoint[2] += (se.values[2] - smoothedPoint[2]) / C;

                    // smoothedPoint[3] is the average of the x, y, and z components, used to
                    // analyze accelerometer data on the graph for counting a step
                    smoothedPoint[3] = 0.3f*Math.abs(smoothedPoint[0]) + 0.3f*Math.abs(smoothedPoint[1]) +
                            0.4f*Math.abs(smoothedPoint[2]);

                    graph.addPoint(smoothedPoint);
                    stepCounter.evaluate(smoothedPoint);
                    stepsTextView.setText(String.valueOf(stepCounter.prevSteps()));
                }
            }
        }

        /**
         * Implementation of finite state machine with 3 states based off of the graph of the
         * human gait. Utilizes a time delay of 200ms and a peak requirement for counting a step.
         */
        class StepCounter {
            final double THRESHOLD = 0.9;
            final int DELTATIME = 200;
            int steps;
            int prevSteps;
            long prevTime;

            State currentState;

            abstract class State {
                abstract public State Evaluate(float[] dataPoint);
            }

            /**
             * state1 loops back to itself until the value of dataPoint[3] exceeds THRESHOLD
             */
            State state1 = new State() {
                public State Evaluate(float[] dataPoint) {
                    prevTime = System.currentTimeMillis();
                    if (dataPoint[3] > THRESHOLD) {
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
                public State Evaluate(float[] dataPoint) {
                    if(dataPoint[3] < THRESHOLD) {
                        if(System.currentTimeMillis() - prevTime > DELTATIME) {
                            steps++;
                            ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                            toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200);
                            prevTime = System.currentTimeMillis();
                            return state3;
                        }
                        return state1;
                    }
                    return state2;
                }
            };

            /**
             * state3 loops back to itself until both dataPoint[3] is below THRESHOLD and the time
             * elapsed is above DELTATIME
             */
           State state3 = new State() {
                public State Evaluate(float[] dataPoint) {
                    if(dataPoint[3] < THRESHOLD) {
                        if(System.currentTimeMillis() - prevTime > DELTATIME) {
                            steps++;
                            ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                            toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200);
                            return state1;
                        }
                    }
                    return state3;
                }
            };

            StepCounter() {
                currentState = state1;
            }

            public void evaluate(float[] dataPoint) {
                currentState = currentState.Evaluate( dataPoint );
            }

            public int prevSteps() {
                return prevSteps;
            }

            public void clearSteps() {
                prevSteps = steps;
                steps = 0;
            }
        }
    }
}