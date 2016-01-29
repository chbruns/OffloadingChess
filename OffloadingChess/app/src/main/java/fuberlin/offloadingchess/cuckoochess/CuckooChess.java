/*
    CuckooChess - A java chess program.
    Copyright (C) 2011  Peter Österlund, peterosterlund2@gmail.com

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package fuberlin.offloadingchess.cuckoochess;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.View.OnTouchListener;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import fuberlin.offloadingchess.chess.ChessParseError;
import fuberlin.offloadingchess.chess.Move;
import fuberlin.offloadingchess.chess.Position;
import fuberlin.offloadingchess.chess.TextIO;
import fuberlin.offloadingchess.guibase.ChessController;
import fuberlin.offloadingchess.guibase.GUIInterface;
import fuberlin.offloadingchess.offloading.Algorithms;
import fuberlin.offloadingchess.offloading.Engine;


public class CuckooChess extends Activity implements GUIInterface {
    ChessBoard cb;
    ChessController ctrl;
    boolean mShowThinking;
    int mTimeLimit;
    int maxDepth;
    boolean playerWhite;
    static final int ttLogSize = 16; // Use 2^ttLogSize hash entries.
    String offloadingServer;

    TableLayout table;

    TextView status;
    ScrollView moveListScroll;
    TextView moveList;
    TextView thinking;
    TextView serverAvaText;
    TextView pingText;
    TextView localTimeEstText;
    TextView serverTimeEstText;
    TextView commTimeEstText;
    TextView offlTimeEstText;
    TextView csrText;
    TextView offlDoneText;
    TextView offlTimeRealText;
    TextView lastExTastText;
    TextView transferedDataText;
    TextView transferredDataMsText;
    TextView realServerTimeText;


    Engine offloadingEngine;

    SharedPreferences settings;
    private boolean forceOffloading;
    private int offloadingCriteria;
    private boolean verboseMode;


    private void readPrefs() {
        mShowThinking = false; // = settings.getBoolean("showThinking", false);
        String timeLimitStr = settings.getString("timeLimit", "5000");
        mTimeLimit = Integer.parseInt(timeLimitStr);
        playerWhite = settings.getBoolean("playerWhite", true);
        boolean boardFlipped = settings.getBoolean("boardFlipped", false);
        cb.setFlipped(boardFlipped);
        ctrl.setTimeLimit();
        String fontSizeStr = settings.getString("fontSize", "12");
        int fontSize = Integer.parseInt(fontSizeStr);
        setFontSize(fontSize);
        forceOffloading = settings.getBoolean("forceOfflaoding", false);
        String offloadingCriteriaStr = settings.getString("offloadingCriteria", "1");
        offloadingCriteria = Integer.parseInt(offloadingCriteriaStr);
        verboseMode = true; // settings.getBoolean("verboseMode", true);
        String strMaxDepth = settings.getString("maxDepth", "6");
        maxDepth = Integer.parseInt(strMaxDepth);
        offloadingServer = settings.getString("server", "192.168.1.2:8080");
        ctrl.setOffloadingServer(offloadingServer);

    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settings = PreferenceManager.getDefaultSharedPreferences(this);
        settings.registerOnSharedPreferenceChangeListener(new OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                readPrefs();
                ctrl.setHumanWhite(playerWhite);
            }
        });

        setContentView(R.layout.main);
        status = (TextView)findViewById(R.id.status);
        moveListScroll = (ScrollView)findViewById(R.id.scrollView);

        readTable();

        thinking = (TextView)findViewById(R.id.thinking);
        cb = (ChessBoard)findViewById(R.id.chessboard);
        status.setFocusable(false);
        moveListScroll.setFocusable(false);
        moveList.setFocusable(false);
        thinking.setFocusable(false);

        if (android.os.Build.VERSION.SDK_INT > 9) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);
            System.out.println("*** My thread is now configured to allow connection");
        }

        ctrl = new ChessController(this);
        offloadingEngine = ctrl.getOffloadingEngine();
        Algorithms.setChessController(ctrl);

        ctrl.setThreadStackSize(32768);
        readPrefs();

        Typeface chessFont = Typeface.createFromAsset(getAssets(), "casefont.ttf");
        cb.setFont(chessFont);
        cb.setFocusable(true);
        cb.requestFocus();
        cb.setClickable(true);

        ctrl.newGame(playerWhite, ttLogSize, false);
        {
            String fen = "";
            String moves = "";
            String numUndo = "0";
            String tmp;
            if (savedInstanceState != null) {
                tmp = savedInstanceState.getString("startFEN");
                if (tmp != null) fen = tmp;
                tmp = savedInstanceState.getString("moves");
                if (tmp != null) moves = tmp;
                tmp = savedInstanceState.getString("numUndo");
                if (tmp != null) numUndo = tmp;
            } else {
                tmp = settings.getString("startFEN", null);
                if (tmp != null) fen = tmp;
                tmp = settings.getString("moves", null);
                if (tmp != null) moves = tmp;
                tmp = settings.getString("numUndo", null);
                if (tmp != null) numUndo = tmp;
            }
            List<String> posHistStr = new ArrayList<String>();
            posHistStr.add(fen);
            posHistStr.add(moves);
            posHistStr.add(numUndo);
            ctrl.setPosHistory(posHistStr);
        }
        ctrl.startGame();

        cb.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (ctrl.humansTurn() && (event.getAction() == MotionEvent.ACTION_UP)) {
                    int sq = cb.eventToSquare(event);
                    Move m = cb.mousePressed(sq);
                    if (m != null) {
                        ctrl.humanMove(m);
                    }
                    return false;
                }
                return false;
            }
        });

        cb.setOnTrackballListener(new ChessBoard.OnTrackballListener() {
            public void onTrackballEvent(MotionEvent event) {
                if (ctrl.humansTurn()) {
                    Move m = cb.handleTrackballEvent(event);
                    if (m != null) {
                        ctrl.humanMove(m);
                    }
                }
            }
        });
        cb.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (!ctrl.computerThinking())
                    showDialog(CLIPBOARD_DIALOG);
                return true;
            }
        });
    }


    /**
     * Reads the TextView elements of the UI
     */
    private void readTable() {
        moveList = (TextView)findViewById(R.id.moveList);
        serverAvaText = (TextView)findViewById(R.id.serverAva2);
        pingText = (TextView)findViewById(R.id.ping2);
        localTimeEstText = (TextView)findViewById(R.id.localTimeEst2);
        serverTimeEstText = (TextView)findViewById(R.id.serverTimeEst2);
        commTimeEstText = (TextView)findViewById(R.id.commTimeEst2);
        offlTimeEstText = (TextView)findViewById(R.id.offlTimeEst2);
        csrText = (TextView)findViewById(R.id.csr2);
        offlDoneText = (TextView)findViewById(R.id.offlDone2);
        offlTimeRealText = (TextView)findViewById(R.id.offlTimeReal2);
        lastExTastText = (TextView)findViewById(R.id.lastTask2);
        transferedDataText = (TextView)findViewById(R.id.transData2);
        transferredDataMsText = (TextView)findViewById(R.id.transDataMs2);
        realServerTimeText = (TextView)findViewById(R.id.serverReal2);
    }

    /**
     * is called to change the font size
     * @param fontSize new font size
     */
    private void setFontSize(int fontSize){

        if (table == null) table = (TableLayout)findViewById(R.id.table1);
        for (int i = 0; i < table.getChildCount(); i++) {
            View v = table.getChildAt(i);
            if (v instanceof TableRow) {
                for (int t = 0; t < ((TableRow) v).getChildCount(); t++) {
                    View v2 = ((TableRow) v).getChildAt(t);
                    if (v2 instanceof TextView) ((TextView) v2).setTextSize(fontSize);
                }
            }
        }

        status.setTextSize(fontSize);
        moveList.setTextSize(fontSize);
        thinking.setTextSize(fontSize);
    }



    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        List<String> posHistStr = ctrl.getPosHistory();
        outState.putString("startFEN", posHistStr.get(0));
        outState.putString("moves", posHistStr.get(1));
        outState.putString("numUndo", posHistStr.get(2));
    }

    @Override
    protected void onPause() {

        List<String> posHistStr = ctrl.getPosHistory();
        Editor editor = settings.edit();
        editor.putString("startFEN", posHistStr.get(0));
        editor.putString("moves", posHistStr.get(1));
        editor.putString("numUndo", posHistStr.get(2));
        offloadingEngine.savePersistentParams();
        editor.commit();
        super.onPause();

    }

    @Override
    protected void onDestroy() {
        ctrl.stopComputerThinking();
        offloadingEngine.unregisterBroadcastReceivers();
        super.onDestroy();

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.options_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.item_new_game:
                ctrl.newGame(playerWhite, ttLogSize, false);
                ctrl.startGame();
                return true;
            case R.id.item_undo:
                ctrl.takeBackMove();
                return true;
            case R.id.item_redo:
                ctrl.redoMove();
                return true;
            case R.id.item_settings:
            {
                Intent i = new Intent(CuckooChess.this, Preferences.class);
                startActivityForResult(i, 0);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 0) {
            readPrefs();
            ctrl.setHumanWhite(playerWhite);
        }
    }

    @Override
    public void setPosition(Position pos) {
        cb.setPosition(pos);
        ctrl.setHumanWhite(playerWhite);
    }

    @Override
    public void setSelection(int sq) {
        cb.setSelection(sq);
    }

    @Override
    public void setStatusString(String str) {
        status.setText(str);
    }

    @Override
    public void setMoveListString(String str) {
       if (!verboseMode) {
           moveList.setText(str);
           moveListScroll.fullScroll(ScrollView.FOCUS_DOWN);
       }

    }

    @Override
    public void setThinkingString(String str) {
        thinking.setText(str);
    }

    @Override
    public int timeLimit() {
        return mTimeLimit;
    }

    public int getMaxDepth() {
        return maxDepth;
    }


    @Override
    public boolean randomMode() {
        return mTimeLimit == -1;
    }

    public int getOffloadingCriteria() {
        return offloadingCriteria;
    }

    public boolean isForceOffloading() {
        return forceOffloading;
    }

    @Override
    public boolean showThinking() {
        return mShowThinking;
    }

    static final int PROMOTE_DIALOG = 0;
    static final int CLIPBOARD_DIALOG = 1;

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case PROMOTE_DIALOG: {
                final CharSequence[] items = {"Queen", "Rook", "Bishop", "Knight"};
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Promote pawn to?");
                builder.setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int item) {
                        ctrl.reportPromotePiece(item);
                    }
                });
                AlertDialog alert = builder.create();
                return alert;
            }
            case CLIPBOARD_DIALOG: {
                final CharSequence[] items = {"Copy Game", "Copy Position", "Paste"};
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Clipboard");
                builder.setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int item) {
                        switch (item) {
                            case 0: {
                                String pgn = ctrl.getPGN();
                                ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                                clipboard.setText(pgn);
                                break;
                            }
                            case 1: {
                                String fen = ctrl.getFEN() + "\n";
                                ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                                clipboard.setText(fen);
                                break;
                            }
                            case 2: {
                                ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                                if (clipboard.hasText()) {
                                    String fenPgn = clipboard.getText().toString();
                                    try {
                                        ctrl.setFENOrPGN(fenPgn);
                                    } catch (ChessParseError e) {
                                        Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                }
                                break;
                            }
                        }
                    }
                });
                AlertDialog alert = builder.create();
                return alert;
            }
        }
        return null;
    }


    @Override
    public void requestPromotePiece() {
        runOnUIThread(new Runnable() {
            public void run() {
                showDialog(PROMOTE_DIALOG);
            }
        });
    }

    @Override
    public void reportInvalidMove(Move m) {
        String msg = String.format("Invalid move %s-%s", TextIO.squareToString(m.from), TextIO.squareToString(m.to));
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void runOnUIThread(Runnable runnable) {
        runOnUiThread(runnable);
    }

    /**
     * Creats the ui output for the offloading stats
     *
     * @param estLocalTime          estimated local runtime
     * @param estServerRuntime      estimated server runtime
     * @param estOffloadingTime     estimated offloading time (overall: server + communication)
     * @param estCommunTime         estimated communication time
     * @param estLocalEnergy        estimated local energy consumption
     * @param estRemoteEnergy       estimated server energy consumption
     * @param estCommunEnergy       estimated communication energy consumption
     * @param ping                  ping
     * @param transferedDataSize    size of data
     * @param transferredBytesMs    bandwith
     * @param overallTime           real execution time
     * @param csrServerDevice       CSR
     * @param serverAvailable       is the server available?
     * @param doOffloading          offloading done?
     * @param algName               offloading-tasks name
     * @param realServerTime        real server execution time
     */
    public void verboseOutput(double estLocalTime, double estServerRuntime, double estOffloadingTime, double estCommunTime, double estLocalEnergy, double estRemoteEnergy, double estCommunEnergy, double ping, double transferedDataSize, double transferredBytesMs, double overallTime, float csrServerDevice, boolean serverAvailable, boolean doOffloading, Algorithms.AlgName algName, double realServerTime) {
        String offloadingOverview = "";
        String strEstLocalTime = "";
        String estServerTime;
        String estOffloadTime = "";
        String strEstCommunTime = "";
        String strEstLocalEnergy = "";
        String strEstRemoteEnergy = "";
        String strEstCommunEnergy = "";
        String strOverallTime = "";
        String strPing = "";
        String strCsr = "";
        String strTransferedDataSize = "";
        String strTransferedBytesMs;
        boolean strServerAvailable;
        boolean offloadingDone;
        String strRealServerTime = "";

        if (estLocalTime == -1) strEstLocalTime = "No Data";
        else strEstLocalTime = Double.toString(round(estLocalTime, 2)) + "ms";

        if (estServerRuntime == -1) estServerTime = "No Data";
        else estServerTime = Double.toString(round(estServerRuntime, 2)) + "ms";

        if (estOffloadingTime == -1) estOffloadTime = "No Data";
        else estOffloadTime = Double.toString(round(estOffloadingTime, 2)) + "ms";

        if (estCommunTime == -1) strEstCommunTime = "No Data";
        else strEstCommunTime = Double.toString(round(estCommunTime, 2)) + "ms";

        if (estLocalEnergy == -1) strEstLocalEnergy = "No Data";
        else strEstLocalEnergy = Double.toString(round(estLocalEnergy, 2));

        if (estRemoteEnergy == -1) strEstRemoteEnergy = "No Data";
        else strEstRemoteEnergy = Double.toString(round(estRemoteEnergy, 2));

        if (estCommunEnergy == -1) strEstCommunEnergy = "No Data";
        else strEstCommunEnergy = Double.toString(round(estCommunEnergy, 2));

        if (ping == -1 || serverAvailable == false) strPing = "No Data";
        else strPing = Double.toString(round(ping, 2));

        if (transferedDataSize == -1) strTransferedDataSize = "No Data";
        else strTransferedDataSize = Double.toString(round(transferedDataSize / 1024, 2)) + "KB";


        if (transferredBytesMs == -1) strTransferedBytesMs = "No Data";
        else strTransferedBytesMs = Double.toString(round(transferredBytesMs * 1000 / 1024, 2)) + "KB/s";

        if (overallTime == -1) strOverallTime = "No Data";
        else strOverallTime = Double.toString(round(overallTime, 2)) + "ms";


        if (realServerTime == -1) strRealServerTime = "No Data";
        else strRealServerTime = Double.toString(round(realServerTime, 2));

        strCsr = Double.toString(round(csrServerDevice, 2));
        strServerAvailable = serverAvailable;
        offloadingDone = doOffloading;
        String lastAlgName = algName.name();

        serverAvaText.setText("" + strServerAvailable);
        pingText.setText(strPing);
        csrText.setText(strCsr);
        localTimeEstText.setText(strEstLocalTime);
        serverTimeEstText.setText(estServerTime);
        commTimeEstText.setText(strEstCommunTime);
        offlTimeEstText.setText(estOffloadTime);
        offlDoneText.setText("" + offloadingDone);
        offlTimeRealText.setText(strOverallTime);
        lastExTastText.setText(lastAlgName);
        transferedDataText.setText(strTransferedDataSize);
        transferredDataMsText.setText(strTransferedBytesMs);
        realServerTimeText.setText(strRealServerTime);

    }

    private double round(double nD, int nDec) {
        return Math.round(nD*Math.pow(10,nDec))/Math.pow(10,nDec);
    }

    private double round(float nF, int nDec) {
        return Math.round(nF * Math.pow(10,nDec)) / Math.pow(10, nDec);
    }

    public boolean isVerboseMode() {
        return verboseMode;
    }

}
