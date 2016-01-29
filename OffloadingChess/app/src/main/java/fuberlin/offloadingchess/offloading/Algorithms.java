package fuberlin.offloadingchess.offloading;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;


import fuberlin.offloadingchess.chess.MoveGen;
import fuberlin.offloadingchess.chess.Position;
import fuberlin.offloadingchess.chess.Search;
import fuberlin.offloadingchess.guibase.ChessController;

public class Algorithms {
	
	public static final int MAX_REPETITIONS = 20;
	
	private static Engine offloadingEngine = null;
	private static DataBaseHelper dbHelper = null;
	private static long currentAlgInputRep = -1;

    private static ChessController ctrl;
    private static Search search;

	public static void setSearch(Search search) {
        Algorithms.search = search;
    }

	public static void setOffloadingEngine(Engine engine) {
		Algorithms.offloadingEngine = engine;
	}
	
	public static String executeLocally(AlgName algName, String... parameters) throws Search.StopSearch {

		switch (algName) {

		case fileAndLoops:
			long nLoops2 = Long.parseLong(parameters[0]); //Parsing of the input parameters
			//Parameters[1] is a file encoded as a String with Base64
			//We only want to test that it was correctly received by returning its size, so no parsing is needed
			int fileLength = fileAndLoops(nLoops2, parameters[1]);
			return Integer.toString(fileLength); //In this case, the output parameter is an Integer so casting to String is needed

		case iterativeDeepening:
            MoveGen.MoveList moves = (MoveGen.MoveList) Serializer.deserialize(parameters[0]);
			int maxDepth = Integer.parseInt(parameters[1]);
            int maxNodes = Integer.parseInt(parameters[2]);
			Position pos = (Position) Serializer.deserialize(parameters[3]);
			currentAlgInputRep = getAlgRep2(moves.size, pos.getPiecesCount());
			//writeIDToFile(algName, parameters);
            return Serializer.serialize(search.iterativeDeepening(moves, maxDepth, maxNodes, true));

		default:
			return "Error";
		}
	}
	
	public static ChessController getChessController() {
        return Algorithms.ctrl;
    }

    public static void setChessController(ChessController ctrl){
        Algorithms.ctrl = ctrl;
    }

	//Returns the predicted number of low level instructions of the algName algorithm for a given input
	public static double getCost(AlgName algName, String... parameters) {
		switch (algName) {

		case fileAndLoops:
			long nLoops2 = Long.parseLong(parameters[0]); //Parsing of the input parameters
			//We have a File encoded as a String in parameters[1], but as fileAndLoops actually behaves like doSomeLoops, we don't need this parameter in order to estimate the cost
			return fileAndLoopsCost(nLoops2);

		case iterativeDeepening:
			//currentAlgInputRep = getAlgRep();
            MoveGen.MoveList moves = (MoveGen.MoveList) Serializer.deserialize(parameters[0]);
			Position pos = (Position) Serializer.deserialize(parameters[3]);
            currentAlgInputRep = getAlgRep2(moves.size, pos.getPiecesCount());
			//writeIDToFile(algName, parameters);
			double runtime = estCostWithDB(algName);
			return runtime;

		default:
			return -1.0;
		}
	}

	/**
	 * this method was used for collecting input data for the AESTET of the offloading-engine
	 * @param algName		name of the offloading-task
	 * @param parameters	input parameter of the offloading-task
	 */
	private static void writeIDToFile(AlgName algName, String... parameters) {
		String toWrite = String.valueOf(currentAlgInputRep);
		for (int i = 0; i < parameters.length; i++) {
			toWrite += ";; " + parameters[i];
		}
		toWrite += "\n";

		try {
			String filename = algName.name() + "_autoCostEstInput.csv";
			File myFile = new File(Environment
					.getExternalStorageDirectory(), filename);
			if (!myFile.exists()) myFile.createNewFile();
			FileWriter fw;
			try {
				fw = new FileWriter(myFile, true);
				fw.write(toWrite);
				fw.flush();
				fw.close();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	//No problem if actually there is no costs DB
	public static void loadAlgCostsDB(Context appContext) {
		dbHelper = new DataBaseHelper(appContext);
		if (dbHelper.isDbInAssets()) {
			//If it has not been done before, copy the DB from the "assets" folder to the "data" folder
			dbHelper.createDataBase();
			//Open the database (we'll keep it open in OPEN_READWRITE mode until onDestroy of the main Activity)
			dbHelper.openDataBase();
		}
	}

	//No problem if actually there is no costs DB
	public static void closeAlgCostsDB() {
		dbHelper.close();
	}
	
	public static boolean isAlgInCostsDB(AlgName algName) {
		if (!dbHelper.isDbInAssets()) return false;
		else {
			if (dbHelper.existsAlg(algName.toString())) return true;
			else return false;
		}
	}
	
	private static double estCostWithDB(AlgName algName) {
		double estRunTimeMs = dbHelper.getRuntime(algName.toString(), currentAlgInputRep, offloadingEngine.getCsrFromAlg(algName));
		return estRunTimeMs; //*Engine.SERVER_INST_MS;
	}
	
	public static void updateCostsDB(AlgName algName, double runtime, boolean serverGen) {


		dbHelper.insertRow(algName.toString(), currentAlgInputRep, runtime, serverGen);
		float recentCsr = dbHelper.getCsr(algName.toString(), currentAlgInputRep, offloadingEngine.getCsrFromAlg(algName));
		if (recentCsr != -1.0) offloadingEngine.updateCsr(algName, recentCsr);
	}
	
	private static int fileAndLoops(long nLoops, String fileContents) {
		long i = 0;
		while (i < nLoops) i++;
		return fileContents.length();
	}

	private static double fileAndLoopsCost(long nLoops) {
		return nLoops * 5.0;
	}
	
	/**
	 * this methode creates an alternative input-ID for the offloading-task iterativeDeepening. It is not used anymore
	 * @return	the ID
	 */
	public static long getAlgRep() {
		Long zobrist = ctrl.getGame().pos.zobristHash();
		String strZobrist = Long.toBinaryString(zobrist);
		Long maxDepth = Long.valueOf(ctrl.getGUI().getMaxDepth());
		String strMaxDepth = Long.toBinaryString(maxDepth);
		String id = strMaxDepth + strZobrist;
		while (id.length() > 64) {
			id = id.substring(0,id.length() - 1);
		}
		Long lID = new BigInteger(id, 2).longValue(); // Long.parseLong(id);

		return (lID < 0) ? (lID * -1) : lID;
	}

	/**
	 * this method creates the input-ID for the offloading-task iterativeDeepening
	 * @param size			size of legal moves
	 * @param piecesCount	count of pieces on the chessboard
	 * @return				the ID
	 */
	public static long getAlgRep2(int size, String piecesCount) {
        long maxDepth = Long.valueOf(ctrl.getGUI().getMaxDepth());
        long moveSize = (long) size;
		String moveSz;
		if (moveSize < 10) {
			moveSz = "0" + String.valueOf(moveSize);
		} else  {
			moveSz = String.valueOf(moveSize);
		}

        return Long.parseLong(String.valueOf(maxDepth + "" + piecesCount + "" + moveSz));
	}

	public static long getCurrentAlgInputRep() {
		return currentAlgInputRep;
	}

    //Add the name of your algorithms to this enumeration
	public static enum AlgName {
		fileAndLoops,
		iterativeDeepening,
	}
}
