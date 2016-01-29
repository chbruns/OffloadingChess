package fuberlin.offloadingchess.offloading;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class DataBaseHelper extends SQLiteOpenHelper {

	public static final String DB_NAME = "algCosts";
	private static final float CSR_FLEX_FACTOR = 5;
	public byte dbInAssets; //-1 means not really initialized, 0 means false and 1 means true
	private String DB_PATH; //The Android's default system path of your application databases
	private SQLiteDatabase algCostsDB;
	private final Context appContext;

	/**
	 * Constructor
	 * Takes and keeps a reference of the passed context in order to access to the application assets and resources.
	 * @param context
	 */
	public DataBaseHelper(Context context) {
		super(context, DB_NAME, null, 1);
		if(android.os.Build.VERSION.SDK_INT >= 17){
			DB_PATH = context.getApplicationInfo().dataDir + "/databases/";
		}
		else
		{
			DB_PATH = "/data/data/" + context.getPackageName() + "/databases/";
		}
		appContext = context;
		dbInAssets = -1;
	}
	
	/****************************************************/
	/** Part1. Necessary methods to get the DB to work **/
	/****************************************************/
	
	public boolean isDbInAssets() {
		if (dbInAssets == -1) {
			InputStream auxInputStream;
			dbInAssets = 0;
			try {
				auxInputStream = appContext.getAssets().open(DB_NAME +  ".db");
				auxInputStream.close();
				dbInAssets = 1;
				return true;
			} catch (IOException e) {
				//The costs DB system is not used
				e.printStackTrace();

			}
			return false;
		}
		else {
			return dbInAssets == 1 ? true : false;
		}
	}

	/**
	 * Check if the database already exists in the "data" folder to avoid re-copying the file each time you open the application.
	 * @return true if it exists, false if it doesn't
	 */
	private boolean isDbInData() {
		SQLiteDatabase checkDB = null;
		try {
			checkDB = SQLiteDatabase.openDatabase(DB_PATH + DB_NAME, null, SQLiteDatabase.OPEN_READONLY);
		} catch (SQLiteException e) {
			//Database doesn't exist yet in Data
		}
		if (checkDB != null) checkDB.close();
		return checkDB != null ? true : false;
	}

	/**
	 * Copies your database from your local "assets" folder to the just created empty database in the
	 * system folder, from where it can be accessed and handled. This is done by transferring a ByteStream.
	 */
	private void copyDataBase() throws IOException {
		InputStream myInput = appContext.getAssets().open(DB_NAME +  ".db"); //Open your local db as the input stream
		OutputStream myOutput = new FileOutputStream(DB_PATH + DB_NAME); //Open the just created empty db as the output stream
		byte[] buffer = new byte[1024]; //Transfer bytes from the inputfile to the outputfile
		int length;
		while ((length = myInput.read(buffer)) > 0) {
			myOutput.write(buffer, 0, length);
		}
		//Close the streams
		myOutput.flush();
		myOutput.close();
		myInput.close();
	}
	
	/**
	 * Creates an empty database on the system and rewrites it with your own database in the "assets" folder
	 */
	public void createDataBase() {
		if (!isDbInData()) { //The database does not exist
			try {
				/*By calling this method and empty database will be created into the default system path
				of your application so we are going to be able to overwrite that database with our database*/
				this.getReadableDatabase();
			} catch (SQLiteException e) {
				//The database cannot be opened
			}
			//Rather copy the database in a separate thread, otherwise large DBs could take long to be copied and would block the UI
			CopyDbThread copyDbThread = new CopyDbThread();
			copyDbThread.start();
		}
	}

	public void openDataBase() throws SQLException {
		try {
			algCostsDB = SQLiteDatabase.openDatabase(DB_PATH + DB_NAME, null, SQLiteDatabase.OPEN_READWRITE );
		} catch (SQLException sqle) {
			//System.out.println("Could not open the database in data: " + sqle.getMessage());
		}
	}

	@Override
	public synchronized void close() {
		if (algCostsDB != null) algCostsDB.close();
		super.close();
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		//Necessary method as this class extends SQLiteOpenHelper, but we won't used it
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		//Necessary method as this class extends SQLiteOpenHelper, but we won't used it
	}
	
	private class CopyDbThread extends Thread {
		
		public void run() {
			try {
				copyDataBase();
			} catch (IOException e) {
				//System.out.println("Error copying database from assets to data: " + e.getMessage());
			}
		}
		
	}
	
	
	/****************************************************/
	/** Part2. Own DB querying / modification methods  **/
	/****************************************************/
	
	public boolean existsAlg(String algName) {
		Cursor cursor = algCostsDB.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='" + algName + "'", null);
		boolean existsAlg = false;
		if (cursor.moveToFirst()) existsAlg = true;
		cursor.close();
		return existsAlg;
	}
	
	public double getRuntime(String algName, long inputRep, float algNameCurrentCsr) {
		
		double averageRuntime = 0.0;
		
		Cursor cursor = algCostsDB.rawQuery("SELECT runTimeMs, serverGen FROM " + algName + " WHERE inputRep=" + inputRep, null);
	    
		if (cursor.moveToFirst()) { //equal inputRep found
			
	    	double runtimesSum = 0;
            do {
            	if (cursor.getInt(1) == 1) runtimesSum += cursor.getDouble(0);
            	else runtimesSum += (cursor.getDouble(0) / algNameCurrentCsr);
            } while (cursor.moveToNext());
            averageRuntime = runtimesSum / (double) cursor.getCount();
            
	    }
		else {
			
			//Calculate k
			Cursor cCountRows = algCostsDB.rawQuery("SELECT COUNT(*) FROM " + algName, null);
			cCountRows.moveToFirst();
			int numRowsAlgName = cCountRows.getInt(0);
			cCountRows.close();
		   
			//k is the number of Nearest Neighbors that we are going to search for, in order to calculate an average of runtimes
			//k is set to be proportional to numberOfObservations^(4/5). Testing showed that the dividing constant 5.0 is good to adjust k.
			double k = Math.pow(numRowsAlgName, 4.0/5.0) / 5.0;
			long kHalf = (long) Math.ceil(k / 2.0); //Use Math.ceil to always get a minimum value of 1
			
			Cursor cPrevious = algCostsDB.rawQuery("SELECT inputRep, runTimeMs, serverGen FROM " + algName + " WHERE inputRep<" + inputRep + " ORDER BY inputRep DESC LIMIT " + kHalf, null);
			Cursor cNext = algCostsDB.rawQuery("SELECT inputRep, runTimeMs, serverGen FROM " + algName + " WHERE inputRep>" + inputRep + " ORDER BY inputRep LIMIT " + kHalf, null);

			//Calculate R
			long R = 0;
			long absDiff;
			if (cPrevious.moveToFirst()) {
	            do {
	            	absDiff = Math.abs(inputRep - cPrevious.getLong(0));
					if (absDiff > R) R = absDiff;
	            } while (cPrevious.moveToNext());
		    }
			if (cNext.moveToFirst()) {
	            do {
	            	absDiff = Math.abs(inputRep - cNext.getLong(0));
					if (absDiff > R) R = absDiff;
	            } while (cNext.moveToNext());
		    }
			
			//Calculate fR(x)
			double fR = 0;
			if (cPrevious.moveToFirst()) {
	            do {
	            	fR += KR((double) Math.abs(inputRep - cPrevious.getLong(0)), (double) R);
	            } while (cPrevious.moveToNext());
		    }
			if (cNext.moveToFirst()) {
	            do {
	            	fR += KR((double) Math.abs(inputRep - cNext.getLong(0)), (double) R);
	            } while (cNext.moveToNext());
		    }
			fR /= k;
			
			//Calculate KR(x-xi)/fR(x) as the weights and obtain the weighted runtime average
			double weight;
			if (cPrevious.moveToFirst()) {
	            do {
					weight =  KR((double) Math.abs(inputRep - cPrevious.getLong(0)), (double) R) / fR;
					if (cPrevious.getInt(2) == 1) averageRuntime += (cPrevious.getDouble(1) * weight);
	            	else averageRuntime += ((cPrevious.getDouble(1) / algNameCurrentCsr) * weight);
	            } while (cPrevious.moveToNext());
		    }
			if (cNext.moveToFirst()) {
	            do {
	            	weight =  KR((double) Math.abs(inputRep - cNext.getLong(0)), (double) R) / fR;
					if (cNext.getInt(2) == 1) averageRuntime += (cNext.getDouble(1) * weight);
	            	else averageRuntime += ((cNext.getDouble(1) / algNameCurrentCsr) * weight);
	            } while (cNext.moveToNext());
		    }
			averageRuntime /= k;
			
			cPrevious.close();
			cNext.close();
			
		}
	    cursor.close();

		//averageRuntime = round(averageRuntime,2);
	    return averageRuntime;
	}

	private static double KR(double u, double R) { //Scaled Epanechnikov Kernel
		return K(u/R) / R;
	}

	private static double K(double u) { //Epanechnikov Kernel
		return 0.75 * (1 - u*u);
	}

	public void insertRow(String algName, long algInputRep, double runTimeMs, boolean serverGen) {
		
		//If there are more than Algorithms.MAX_REPETITIONS rows with this inputRep, delete one of them randomly
		String subQuery1 = "(SELECT COUNT(*) FROM " + algName + " WHERE inputRep=?)";
		String subQuery2 = "(SELECT _id FROM " + algName + " WHERE inputRep=? ORDER BY RANDOM() LIMIT 1)";
		algCostsDB.delete(
				algName,
				Algorithms.MAX_REPETITIONS + "<=" + subQuery1 + " AND _id=" + subQuery2,
				new String[] { Long.toString(algInputRep), Long.toString(algInputRep) }
		);
		
		ContentValues contentValues = new ContentValues();
        contentValues.put("inputRep", algInputRep);
        contentValues.put("runTimeMs", runTimeMs);
        contentValues.put("serverGen", serverGen);
        algCostsDB.insert(algName, null, contentValues);
		
	}
	
	/**
	 * Returns the computation speed relation (CSR) deduced from averaging the server generated runtimes
	 * and the Android generated runtimes associated to this algInputRep. If this algInputRep
	 * is not in the DB, or it only has server generated runtimes associated, or it only
	 * has Android runtimes associated, no CSR can be deduced and returns -1.0
	 */
	public float getCsr(String algName, long algInputRep, float algNameCurrentCsr) {
		float returnCsr = -1;
		Cursor cursor = algCostsDB.rawQuery("SELECT runTimeMs, serverGen FROM " + algName + " WHERE inputRep=" + algInputRep, null);
		if (cursor.moveToFirst()) {
			double sumRuntimesServerGen = 0;
			double sumRuntimesAndroidGen = 0;
			double serverRows = 0;
			double androidRows = 0;
            do {
            	if (cursor.getInt(1) == 1) {
            		sumRuntimesServerGen += cursor.getDouble(0);
            		serverRows++;
            	}
            	else {
            		sumRuntimesAndroidGen += cursor.getDouble(0);
            		androidRows++;
            	}
            } while (cursor.moveToNext());
            if (serverRows > 0 && androidRows > 0) {
            	double averageAndroidRows = sumRuntimesAndroidGen/androidRows;
                double averageServerRows = sumRuntimesServerGen/serverRows;
                //Too small values could produce inaccurate Csr's
                if (averageAndroidRows > Engine.MIN_RELEVANT_TIME && averageServerRows > (Engine.MIN_RELEVANT_TIME / algNameCurrentCsr)) {
                	returnCsr = (float) (averageAndroidRows / averageServerRows);
                	//We consider invalid the newly calculated Csr if it is bigger than the DataBaseHelper.CSR_FLEX_FACTOR multiplied per the current Csr, or smaller than the current Csr divided by the same factor
                	if (returnCsr > (algNameCurrentCsr * DataBaseHelper.CSR_FLEX_FACTOR) || returnCsr < (algNameCurrentCsr / DataBaseHelper.CSR_FLEX_FACTOR)) returnCsr = -1;
                }
            }
	    }
	    cursor.close();
		return returnCsr;
	}

	public static double round(double value, int places) {
		if (places < 0) throw new IllegalArgumentException();

		BigDecimal bd = new BigDecimal(value);
		bd = bd.setScale(places, RoundingMode.HALF_UP);
		return bd.doubleValue();
	}

}