/*  vim: set sw=4 tabstop=4 fileencoding=UTF-8:
 *
 *  Copyright 2014 Matthias Wimmer
 *  		  2015 Dominik Meyer
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package de.hosenhasser.funktrainer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import java.text.DateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import de.hosenhasser.funktrainer.data.Question;
import de.hosenhasser.funktrainer.data.QuestionSelection;
import de.hosenhasser.funktrainer.data.Repository;
import de.hosenhasser.funktrainer.views.QuestionView;

public class AdvancedQuestionAsker extends Activity {
    private Repository repository;
    private int currentQuestion;
    private int currentQuestionId;
    private int topicId;
    private int maxProgress;
    private int currentProgress;
    private boolean showingCorrectAnswer;
    private Date nextTime;
    private Timer waitTimer;
    private boolean showingStandardView;

    private SharedPreferences mPrefs;
    private boolean mUpdateNextAnswered;

    private ViewFlipper viewFlipper;
    private GestureDetector gestureDetector;

    private int historyPosition = 0;
    private LinkedList<HistoryEntry> history = new LinkedList<HistoryEntry>();

    private static final int MAX_HISTORY_LENGTH = 30;

    @Override
    public void onDestroy() {
        super.onDestroy();

        cancelTimer();

        repository.close();
        repository = null;
        nextTime = null;
    }

    @Override
    public void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(getClass().getName() + ".showingCorrectAnswer", showingCorrectAnswer);
        outState.putInt(getClass().getName() + ".currentQuestion", currentQuestion);
        outState.putInt(getClass().getName() + ".maxProgress", maxProgress);
        outState.putInt(getClass().getName() + ".currentProgress", currentProgress);
        outState.putLong(getClass().getName() + ".topic", topicId);
        if (nextTime != null) {
            outState.putLong(getClass().getName() + ".nextTime", nextTime.getTime());
        }
    }

    private class CustomGestureDetector extends GestureDetector.SimpleOnGestureListener {
        /*
         * inspired by http://codetheory.in/android-viewflipper-and-viewswitcher/
         *         and https://stackoverflow.com/questions/4139288/android-how-to-handle-right-to-left-swipe-gestures
         */
        private static final int SWIPE_DISTANCE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;

        @Override
        public boolean onDown(MotionEvent e) {
            return false;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float distanceX = e2.getX() - e1.getX();
            float distanceY = e2.getY() - e1.getY();


            if (Math.abs(distanceX) > Math.abs(distanceY) && Math.abs(distanceX) > SWIPE_DISTANCE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                if (distanceX > 0) {
                    // Swipe left (next)
                    flipRight();
                } else {
                    // Swipe right (previous)
                    flipLeft();
                }
            }

            return super.onFling(e1, e2, velocityX, velocityY);
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            return false;
        }
    }

    private void updateHistoryView() {
        final HistoryEntry histentry = history.get(history.size() - historyPosition);
        TextView oldQuestionTextNumber = (TextView)findViewById(R.id.oldQuestionHeatTextNumber);
        oldQuestionTextNumber.setText(Integer.toString(-historyPosition));

        final TextView referenceText = (TextView) findViewById(R.id.referenceTextold);
        referenceText.setText(histentry.getReferenceText());

        final QuestionView questionView = (QuestionView) findViewById(R.id.questionViewOld);
        questionView.setHistoryEntry(histentry);

    }

    private void flipRight() {
        //Log.i("Funktrainer", "flip right");
        if (!showingStandardView) {
            return;
        }
        if(history.size() > historyPosition) {
            int historyPositionOld = historyPosition;
            historyPosition = Math.min(historyPosition + 1, history.size() + 1);
            if(historyPositionOld <= 0 && historyPosition > 0) {
                viewFlipper.showPrevious();
            }
            if(historyPosition > 0) {
                updateHistoryView();
            }
        }
    }

    private void flipLeft() {
        //Log.i("Funktrainer", "flip left");
        if (!showingStandardView) {
            return;
        }
        if (historyPosition > 0) {
            int historyPositionOld = historyPosition;
            historyPosition = Math.max(historyPosition - 1, 0);
            if(historyPositionOld > 0 && historyPosition <= 0) {
                viewFlipper.showNext();
            }
            if(historyPosition > 0) {
                updateHistoryView();
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUpdateNextAnswered = true;

        repository = new Repository(this);
        mPrefs = getSharedPreferences("advanced_question_asker_shared_preferences", MODE_PRIVATE);

        showStandardView();

        viewFlipper = (ViewFlipper)findViewById(R.id.questionAskerViewFlipper);
        viewFlipper.setInAnimation(this, android.R.anim.fade_in);
        viewFlipper.setOutAnimation(this, android.R.anim.fade_out);
        CustomGestureDetector customGestureDetector = new CustomGestureDetector();
        gestureDetector = new GestureDetector(this, customGestureDetector);

        final Button backToQuestionButton = (Button)findViewById(R.id.backToQuestionButton);
        backToQuestionButton.setOnClickListener(new View.OnClickListener() {

            // @Override
            public void onClick(View v) {
                historyPosition = 0;
                viewFlipper.showNext();
            }
        });

        if (savedInstanceState != null) {
            topicId = (int) savedInstanceState.getLong(getClass().getName() + ".topic");
            currentQuestion = savedInstanceState.getInt(getClass().getName() + ".currentQuestion");
            final long nextTimeLong = savedInstanceState.getLong(getClass().getName() + ".nextTime");
            nextTime = nextTimeLong > 0L ? new Date(nextTimeLong) : null;
            showingCorrectAnswer = savedInstanceState.getBoolean(getClass().getName() + ".showingCorrectAnswer");
            maxProgress = savedInstanceState.getInt(getClass().getName() + ".maxProgress");
            currentProgress = savedInstanceState.getInt(getClass().getName() + ".currentProgress");

            showQuestion();
        } else {
            Bundle intbundle = getIntent().getExtras();
            if(intbundle != null) {
                topicId = (int) getIntent().getExtras().getLong(getClass().getName() + ".topic");
                if (topicId != 0) {
                    nextQuestion();
                } else {
                    int questionId = getIntent().getExtras().getInt(getClass().getName() + ".questionId");
                    // String questionReference = getIntent().getExtras().getString(getClass().getName() + ".questionReference");
                    nextQuestion(questionId);
                }
            } else {
                int lastQuestionShown = mPrefs.getInt("last_question_shown", 1);
                currentQuestionId = lastQuestionShown;
                nextQuestion(lastQuestionShown);
            }
        }
    }

    protected void onPause() {
        super.onPause();
        SharedPreferences.Editor ed = mPrefs.edit();
        ed.putInt("last_question_shown", currentQuestionId);
        ed.apply();
    }

    /**
     * Populate the options menu.
     *
     * @param menu the menu to populate
     * @return always true
     */
    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.askermenu, menu);
        return true;
    }

    /**
     * Handle option menu selections.
     *
     * @param item the Item the user selected
     */
    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        // handle item selection
        switch (item.getItemId()) {
            case R.id.resetTopic:
                askRestartTopic();
                return true;
            case R.id.statistics:
                final Intent intent = new Intent(this, StatisticsActivity.class);
                intent.putExtra(StatisticsActivity.class.getName() + ".topic", topicId);
                startActivity(intent);
                return true;
            case R.id.showFormelsammlung:
                final Intent intentFormelsammlung = new Intent(this, FormelsammlungViewerActivity.class);
                startActivity(intentFormelsammlung);
                return true;
            case R.id.showLichtblick:
                final Intent intentLichtblick = new Intent(this, LichtblickeViewerActivity.class);
                final Question question = repository.getQuestion(currentQuestion);
                final int lichtblickPage = question.getLichtblickPage();
                intentLichtblick.putExtra(LichtblickeViewerActivity.class.getName() + ".lichtblickPage", lichtblickPage);
                startActivity(intentLichtblick);
                return true;
//            case R.id.reportError:
//                final StringBuilder uri = new StringBuilder();
//			    uri.append("http://funktrainer.hosenhasser.de/app/reportError?view=QuestionAsker&Reference=" + Integer.toString(this.currentQuestion));
//			    final Intent intent1 = new Intent(Intent.ACTION_VIEW);
//		    	intent1.setData(Uri.parse(uri.toString()));
//	    		startActivity(intent1);
//    			return true;
//			case R.id.help:
//				final StringBuilder uri = new StringBuilder();
//				uri.append("http://funktrainer.hosenhasser.de/app/help?question=");
//				uri.append(currentQuestion);
//				uri.append("&topic=");
//				uri.append(topicId);
//				uri.append("&view=QuestionAsker");
//				final Intent intent2 = new Intent(Intent.ACTION_VIEW);
//				intent2.setData(Uri.parse(uri.toString()));
//				startActivity(intent2);
//				return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }


    /**
     * Restarts the topic after asking for confirmation.
     */
    private void askRestartTopic() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.warningReset);
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.resetOkay, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                restartTopic();
            }
        });
        builder.setNegativeButton(R.string.resetCancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
            }
        });
        final AlertDialog alert = builder.create();
        alert.show();
    }

    private void restartTopic() {
        repository.resetTopic(topicId);
        nextQuestion();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        super.dispatchTouchEvent(event);
        return gestureDetector.onTouchEvent(event);
    }

    private void showStandardView() {
        setContentView(R.layout.question_asker);
        showingStandardView = true;

        ProgressBar progress = (ProgressBar) findViewById(R.id.progressBar1);
        progress.setMax(5);

        final QuestionView questionView = (QuestionView) findViewById(R.id.questionView);
        final Button contButton = (Button) findViewById(R.id.button1);
        final Button skipButton = (Button) findViewById(R.id.skipButton);

        // only enable continue when answer is selected
        questionView.setOnRadioCheckedListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                contButton.setEnabled(questionView.getCheckedRadioButtonId() != -1);
            }

        });

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        boolean show_skip = sharedPref.getBoolean("pref_show_skip_button", false);
        if (show_skip) {
            skipButton.setVisibility(View.VISIBLE);
            skipButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    questionView.setRadioGroupEnabled(true);
                    nextQuestion();
                }
            });
        } else {
            skipButton.setVisibility(View.INVISIBLE);
        }

        contButton.setOnClickListener(new View.OnClickListener() {
            // @Override
            public void onClick(View v) {
                // find what has been selected
                if (showingCorrectAnswer) {
                    showingCorrectAnswer = false;
                    questionView.setRadioGroupEnabled(true);
                    nextQuestion();
                    return;
                }

                final Question question = repository.getQuestion(currentQuestion);
                HistoryEntry histentry = new HistoryEntry();
                histentry.setReferenceText(question.getReference());
                histentry.setQuestionText(question.getQuestion());
                histentry.setHelpText(question.getHelp());
                histentry.setAnswersText(question.getAnswers());
                histentry.setAnswersHelpText(question.getAnswersHelp());
                histentry.setCorrectAnswer(0);
                LinkedList<Integer> historder = new LinkedList<Integer>();
                List<Integer> order = questionView.getOrder();
                for(int i = 0; i < order.size(); i++) {
                    historder.add(order.get(i));
                }
                histentry.setOrder(historder);

                int selectedButton = questionView.getCheckedRadioButtonId();

                histentry.setAnswerGiven(questionView.getPositionOfButton(selectedButton));
                history.add(histentry);

                if(history.size() > MAX_HISTORY_LENGTH) {
                    history.remove();
                }

                if (selectedButton == questionView.getCorrectChoice()) {
                    Toast.makeText(AdvancedQuestionAsker.this, getString(R.string.right), Toast.LENGTH_SHORT).show();

                    if(mUpdateNextAnswered) {
                        repository.answeredCorrect(currentQuestion);
                    }
                    mUpdateNextAnswered = true;

                    nextQuestion();

                    // return;
                } else if (selectedButton != -1) {
                    if(mUpdateNextAnswered) {
                        repository.answeredIncorrect(currentQuestion);
                    }
                    mUpdateNextAnswered = true;

                    showingCorrectAnswer = true;
                    questionView.setEnabled(false);
                    questionView.showCorrectAnswer();

                    // return;
                } else {
                    Toast.makeText(AdvancedQuestionAsker.this, getString(R.string.noAnswerSelected), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void nextQuestion() {
        this.nextQuestion(null, -1);
    }

    private void nextQuestion(final int questionId) {
        this.nextQuestion(null, questionId);
    }

    private void nextQuestion(final String questionReference, final int questionId) {
        if (!showingStandardView) {
            showStandardView();
        }

        final Button contButton = (Button) findViewById(R.id.button1);
        contButton.setEnabled(false);

        QuestionSelection nextQuestion;
        if (questionReference != null) {
            nextQuestion = repository.selectQuestionByReference(questionReference);
            topicId = repository.getFirstTopicIdForQuestionReference(questionReference);
            this.mUpdateNextAnswered = false;
        } else if(questionId != -1) {
            nextQuestion = repository.selectQuestionById(questionId);
            topicId = repository.getFirstTopicIdForQuestionId(questionId);
        } else {
            nextQuestion = repository.selectQuestionByTopicId(topicId);
        }

        // any question?
        final int selectedQuestion = nextQuestion.getSelectedQuestion();
        if (selectedQuestion != 0) {
            currentQuestion = selectedQuestion;
            maxProgress = nextQuestion.getMaxProgress();
            currentProgress = nextQuestion.getCurrentProgress();
            nextTime = null;
            showQuestion();
            return;
        }

        nextTime = nextQuestion.getNextQuestion();
        if (nextTime != null) {
            showQuestion();
            return;
        }

        showingStandardView = false;
        setContentView(R.layout.no_more_questions_finished);

        final Button restartTopicButton = (Button) findViewById(R.id.restartTopic);
        restartTopicButton.setOnClickListener(new View.OnClickListener() {
            //@Override
            public void onClick(View v) {
                restartTopic();
                nextQuestion();
            }

        });
        // return;
    }

    private void showQuestion() {
        if (nextTime != null) {
            showingStandardView = false;
            setContentView(R.layout.no_more_questions_wait);

            final TextView nextTimeText = (TextView) findViewById(R.id.nextTimeText);
            if (nextTime.getTime() - new Date().getTime() < 64800000L) {
                nextTimeText.setText(DateFormat.getTimeInstance().format(nextTime));
            } else {
                nextTimeText.setText(DateFormat.getDateTimeInstance().format(nextTime));
            }
            showNextQuestionAt(nextTime);

            final Button resetWaitButton = (Button) findViewById(R.id.resetWait);
            resetWaitButton.setOnClickListener(new View.OnClickListener() {
                //@Override
                public void onClick(View v) {
                    cancelTimer();
                    repository.continueNow(topicId);
                    nextQuestion();
                    // return;
                }

            });
            return;
        }

        final Question question = repository.getQuestion(currentQuestion);
        currentQuestionId = question.getId();

        final TextView levelText = (TextView) findViewById(R.id.levelText);
        levelText.setText(question.getLevel() == 0 ? getString(R.string.firstPass) :
                question.getLevel() == 1 ? getString(R.string.secondPass) :
                        question.getLevel() == 2 ? getString(R.string.thirdPass) :
                                question.getLevel() == 3 ? getString(R.string.fourthPass) :
                                        question.getLevel() == 4 ? getString(R.string.fifthPass) :
                                                String.format(getString(R.string.passText), question.getLevel()));

        final TextView referenceText = (TextView) findViewById(R.id.referenceText);
        referenceText.setText(question.getReference());

        final QuestionView questionView = (QuestionView) findViewById(R.id.questionView);
        questionView.setQuestion(question);

        final ProgressBar progressBar = (ProgressBar) findViewById(R.id.progressBar1);
        progressBar.setMax(maxProgress);
        progressBar.setProgress(currentProgress);

//        // remove previous question image if any
//        final LinearLayout linearLayout = (LinearLayout) findViewById(R.id.linearLayout1);
//        for (int i = 0; i < linearLayout.getChildCount(); i++) {
//            final View childAtIndex = linearLayout.getChildAt(i);
//            if (childAtIndex instanceof ImageView) {
//                linearLayout.removeViewAt(i);
//                break;
//            }
//        }
    }

    private void showNextQuestionAt(final Date when) {
        scheduleTask(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    //@Override
                    public void run() {
                        nextQuestion();
                    }
                });
            }

        }, when);
    }

    private synchronized void scheduleTask(final TimerTask task, final Date when) {
        cancelTimer();
        waitTimer = new Timer("waitNextQuestion", true);
        waitTimer.schedule(task, when);
    }

    private synchronized void cancelTimer() {
        if (waitTimer != null) {
            waitTimer.cancel();
            waitTimer = null;
        }
    }
}
