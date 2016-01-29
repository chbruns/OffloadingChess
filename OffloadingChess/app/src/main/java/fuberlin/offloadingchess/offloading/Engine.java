package fuberlin.offloadingchess.offloading;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Environment;
import android.telephony.TelephonyManager;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import fuberlin.offloadingchess.chess.Search;
import fuberlin.offloadingchess.guibase.GUIInterface;
import fuberlin.offloadingchess.offloading.Algorithms.AlgName;


public class Engine {

	public static final double SERVER_INST_MS = 6666666; //Calculated through practical values (javap -c), although we know the server has 4 cores of 2.5GHz
	public static final double MIN_RELEVANT_TIME = 15.0;
	private static final String PREFS_NAME = "OffloadingEnginePrefs";
	private final Context appContext; //The context of the main Activity of the Android application using this engine
	private final GUIInterface gui;
	//Persistent parameters
	SharedPreferences sPrefs; //Provides access to the persistent variables
	Map<AlgName, CsrPair> algCsrs;
	ExecutorService executor;
	//Constants
	private String server =   "http://192.168.1.2:8080/offload/run"; // "https://www.mi.fu-berlin.de/offload/run";  Right now we assume we only have 1 server (in the future we could search the nearest server)
	private int pingCounter; //Needed to calculate the ping
	private double timePingStart; //Needed to calculate the ping
	private double[] pingsArray; //Needed to calculate the ping
	private double ping; //Represents the time to query and get an answer from the server (actually not done with a real ping command over ICMP)
	private double transferredBytesMs; //Indicates the quality of the connection bandwidth
	private double transferredBytesMsUpdatesCounter;
	private boolean connAvailable;
	private boolean serverAvailable;
	private String connType; //Wi-Fi, 3G or Other. Not needed, just to display info.
	//Parameters with information about the last offloading attempt
	private boolean doOffloading; //True if start an offloading process was decided, false otherwise
	private double estLocalTime; //The estimation (in milliseconds) of the runtime in the Android mobile device of the potentially offloadable code
	private double estLocalEnergy;
	private double estOffloadingTime; //The estimation (in milliseconds) of the offloading process duration
	private double estServerRuntime; //The estimation (in milliseconds) of the runtime in the server of the potentially offloadable code
	private double estCommunTime;
	private double estCommunEnergy;
	private double estRemoteEnergy;
	private double overallTime; //The time (in milliseconds) that took the execution of an algorithm (both if it was executed locally or offloaded to the server)
	private double overallEnergy;
	private double realServerTime; //The time (in milliseconds) that the server needed to execute an algorithm (in case of offloading). Not needed, just to display info.
	private double parametersSize; //The sum of the sizes of each of the parameters that were sent to the server
	private double overhead; //The overhead that produces estimating the execution times of the potentially offloadable tasks
	private double transferDataTime;
	private double P_m=0.75,P_i=0.5,P_tr=1.2;
	private float transferedDataSize;
	private AlgName algName;
	private long currentAlgID;
    private String gameId;
    private int decisionIndication;
	private NetworkReceiver networkStatusReceiver;
	//To be called onCreate in your Activity
	public Engine(Context theContext) {

		transferredBytesMsUpdatesCounter = 0;

		appContext = theContext;
		gui = (GUIInterface) theContext;
		sPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		Algorithms.setOffloadingEngine(this);

		//We initialize some variables with default values although they will be properly obtained soon (just in case there is an early potentially offloadable algorithm)
		ping = -1;
		transferredBytesMs = 200;
		connAvailable = false;
		serverAvailable = false;
		connType = "Unknown";
		estLocalTime = -1;
		estOffloadingTime = -1;
		estServerRuntime = -1;
		estCommunTime=-1;
		estCommunEnergy=-1;
		estLocalEnergy=-1;
		estRemoteEnergy=-1;
		realServerTime = -1;
		overallTime = -1;
		overallEnergy=-1;
		overhead = -1;
		transferDataTime=-1;
		loadPersistentParams(); //Load the stored parameters of the engine
		keepNetworkInfoUpdated(); //Register a listener to keep updated the network information
		Algorithms.loadAlgCostsDB(appContext);
	}

	private static String getElementValueFromXML(String xmlString, String tagName)
			throws ParserConfigurationException, SAXException, IOException {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		InputSource is = new InputSource();
		is.setCharacterStream(new StringReader(xmlString));
		Document doc = db.parse(is);
		NodeList summary = doc.getElementsByTagName(tagName);
		Element line = (Element) summary.item(0);
		return getCharacterDataFromElement(line);
	}

	private static String getCharacterDataFromElement(Element e) {
		Node child = e.getFirstChild();
		if (child instanceof CharacterData) {
			CharacterData cd = (CharacterData) child;
			return cd.getData();
		}
		return "";
	}
	
	private static double calcAverage (double[] valuesArray) {

		//Calculate the average
		double valuesSum = 0;
		for (int i = 0; i < valuesArray.length; i++) {
			valuesSum += valuesArray[i];
		}
		double average = valuesSum / ((double) valuesArray.length);

		//Recalculate the average omitting all values with a high deviation
		double niceValuesSum = 0;
		double niceValuesCount = 0;
		double auxValue, auxAverage;
		for (int i = 0; i < valuesArray.length; i++) {
			auxValue = valuesArray[i];
			if (auxValue < 0) auxValue *= -1;
			auxAverage = average;
			if (auxAverage < 0) auxAverage *= -1;
			if (auxValue <= auxAverage * 2) {
				niceValuesSum += valuesArray[i];
				niceValuesCount++;
			}
		}
		return niceValuesSum/niceValuesCount;
	}

    public void setGameId(String gameId) {
        this.gameId = gameId;
    }

    public void setServer(String server) {
        this.server = "http://" + server + "/offload/run";
    }

	//Getters, not needed, just to display info
	public double getPing() {
		return ping;
	}
	
	public float getCsrFromAlg(AlgName algName) {
		return algCsrs.get(algName).csrServerDevice;
	}
	
	public float getCsrUpdCountFromAlg(AlgName algName) {
		return algCsrs.get(algName).csrUpdatesCounter;
	}
	
	public double getTransferredBytesMs() {
		return transferredBytesMs;
	}
	
	public String getConnType() {
		return connType;
	}
	
	public boolean isConnectionAvailable() {
		return connAvailable;
	}
	
	public boolean isServerAvailable() {
		return serverAvailable;
	}
	
	public double getRealServerTime() {
		return realServerTime;
	}

	public double getEstLocalTime() {
		return estLocalTime;
	}

	public double getEstLocalEnergy() {
		return estLocalEnergy;
	}
	
	public double getEstOffloadingTime() {
		return estOffloadingTime;
	}

	public double getEstServerRuntime() {
		return estServerRuntime;
	}

	public double getEstCommunTime() {
		return estCommunTime;
	}

	public double getEstCommunEnergy() {
		return estCommunEnergy;
	}

	public double getEstRemoteEnergy() {
		return estRemoteEnergy;
	}
	
	public boolean isOffloadingDone() {
		return doOffloading;
	}

	public double getOverallTime() {
		return overallTime;
	}

	public double getTransferTime() {
		return transferDataTime;
	}

	public double getOveralEnergy() {
		return overallEnergy;
	}

	public double getEstimsOverhead() {
		return overhead;
	}
	//End getters
	
	public float getTransferedDataSize() {return transferedDataSize;}
	
	private void loadPersistentParams() {
		algCsrs = new EnumMap<AlgName, CsrPair>(AlgName.class);
		AlgName[] algNamesEnum = AlgName.values();
		for (int i = 0; i < algNamesEnum.length; i++) {
			CsrPair itCsrPair = new CsrPair();
			itCsrPair.csrServerDevice = sPrefs.getFloat(algNamesEnum[i] + "Csr", -1);
			itCsrPair.csrUpdatesCounter = sPrefs.getFloat(algNamesEnum[i] + "Count", 0);
			algCsrs.put(algNamesEnum[i], itCsrPair);
		}
	}
	
	//To be called onPause in your Activity
	public void savePersistentParams() {
		SharedPreferences.Editor sPrefsEditor = sPrefs.edit();
		Iterator<AlgName> enumKeySet = algCsrs.keySet().iterator();
        while (enumKeySet.hasNext()) {
        	AlgName itAlgName = enumKeySet.next();
        	CsrPair itCsrPair = algCsrs.get(itAlgName);
        	sPrefsEditor.putFloat(itAlgName + "Csr", itCsrPair.csrServerDevice);
    		sPrefsEditor.putFloat(itAlgName + "Count", itCsrPair.csrUpdatesCounter);
        }
		sPrefsEditor.commit();
	}
	
	private void keepNetworkInfoUpdated() {
		networkStatusReceiver = new NetworkReceiver();
		IntentFilter connFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
		appContext.registerReceiver(networkStatusReceiver, connFilter);
	}
	
	/* 
	 * Updates the Csr and/or the costs DB when needed.
	 * Needs to read global variables to check the state of the offloading procedure.
	 * There are two systems to calculate the cost estimations:
	 * 1. The developer provides a function to do so
	 * 2. The developer provides a DB with input-cost pairs (can be generated in our server)
	 * Depending on which system has been used for the current case (algName) there will be different updating needs
	 */

	//To be called onDestroy in your Activity, undoes the changes made by keepNetworkInfoUpdated() and closes the DataBaseHelper
	public void unregisterBroadcastReceivers() {
		if (networkStatusReceiver != null) appContext.unregisterReceiver(networkStatusReceiver);
		Algorithms.closeAlgCostsDB();
	}

	//Updates the Csr for the algorithm algName
	public void updateCsr(AlgName algName, float recentCsr) {
		CsrPair csrPair = algCsrs.get(algName);
		csrPair.csrServerDevice = csrPair.csrServerDevice * csrPair.csrUpdatesCounter / (csrPair.csrUpdatesCounter+1) + recentCsr / (csrPair.csrUpdatesCounter+1);
		if (csrPair.csrUpdatesCounter < Algorithms.MAX_REPETITIONS) csrPair.csrUpdatesCounter++;
	}
	
	/*
	 * Execute an algorithm in the Android device with a known cost in the server,
	 * in order to establish a computation speed relation (how many times faster the server is)
	 * This function will be called during the first offloading attempt of an Android application using this engine
	 */
	private void calculateRelation() throws Search.StopSearch {
		double startAlgorithmTime = ((double) System.nanoTime()) / 1000000.0;
		Algorithms.executeLocally(AlgName.fileAndLoops, new String[]{Long.valueOf(100000000).toString(), "aaaaa"});
		double endAlgorithTime = ((double) System.nanoTime()) / 1000000.0;
		double timeTaken = endAlgorithTime - startAlgorithmTime;
		double AndroidInstMs = Algorithms.getCost(AlgName.fileAndLoops, Long.valueOf(100000000).toString()) / timeTaken;
		float firstTimeCsr = (float) (SERVER_INST_MS / AndroidInstMs);
		Iterator<AlgName> enumKeySet = algCsrs.keySet().iterator();
        while (enumKeySet.hasNext()) {
        	AlgName itAlgName = enumKeySet.next();
        	CsrPair itCsrPair = algCsrs.get(itAlgName);
        	itCsrPair.csrServerDevice = firstTimeCsr;
        	itCsrPair.csrUpdatesCounter++;
        }
	}

	/*
	 * Decides where to execute the algorithm, locally (no offloading) or on the server (offloading is done).
	 * Returns true if the decision is to offload, false otherwise.
	 */
	private boolean decide(AlgName algName,int decision_indicate, String... parameters) throws Search.StopSearch {

		estLocalTime = -1;
		estOffloadingTime = -1;
		estServerRuntime = -1;
		estCommunTime=-1;
		estCommunEnergy=-1;
		estLocalEnergy=-1;
		estRemoteEnergy=-1;

		if (connAvailable && serverAvailable) {

			//Only the very first time that there is the possibility to do offloading, a initial computation speed relation is calculated
            float test = getCsrUpdCountFromAlg(algName);
            if (getCsrUpdCountFromAlg(algName) == 0) {
                calculateRelation();
            }

			double startAlgorithmTime = ((double) System.nanoTime()) / 1000000.0;
			estServerRuntime = Algorithms.getCost(algName, parameters); // / SERVER_INST_MS; //Estimated server execution time
			overhead = ((double) System.nanoTime()) / 1000000.0 - startAlgorithmTime;
            float test2 = getCsrFromAlg(algName);
			estLocalTime = estServerRuntime * getCsrFromAlg(algName); //Estimated Android execution time
			for (int i = 0; parameters != null && i < parameters.length; i++) parametersSize += parameters[i].length();
			estOffloadingTime = ping + estServerRuntime + parametersSize/transferredBytesMs;
			estCommunTime=ping + parametersSize/transferredBytesMs;

			estCommunEnergy=P_tr*estCommunTime;
			estLocalEnergy=P_m*estLocalTime;
			estRemoteEnergy=P_i*estServerRuntime+estCommunEnergy;
			//Time saving criteria
		//	return estOffloadingTime < estLocalTime;

				switch (decision_indicate)
				{
					//Time saving criteria
					case 0:
					{return estOffloadingTime < estLocalTime;}

					//Energy saving criteria
					case 1:
					{return estRemoteEnergy < estLocalEnergy;}
					case 2:
					{return (estRemoteEnergy < estLocalEnergy)&(estOffloadingTime < estLocalTime);}
					default:
						return false;
				}
			}

	return false;

	}

	/*
	 * To be called wherever in your Activity, replacing where you had a potentially offloadable part of code.
	 * Decides where to execute the algorithm, locally (no offloading) or on the server (offloading is done), executes it and retrieves the result.
	 * Returns the result on success (in String form), or the String "Error" on failure.
	 */
	public String execute(boolean forceOffloading, AlgName algName, int decision_indicate, String... parameters) throws Search.StopSearch {

		this.algName = algName;
		this.decisionIndication = decision_indicate;
		parametersSize = 0;
		overhead = -1;

		if (forceOffloading) doOffloading = true;
		else doOffloading = decide(algName, decision_indicate, parameters);

		String algResult = "";
		realServerTime = -1;
		overallTime = -1;

		transferedDataSize = -1;

		double startTime = ((double) System.nanoTime()) / 1000000.0;

		if (doOffloading) { //Do offloading
			int execParamsLength = 1;
			if (parameters != null) execParamsLength += parameters.length;
			String[] execParams = new String[execParamsLength];
			execParams[0] = algName.toString();
			for (int i = 1; i < execParams.length; i++) execParams[i] = parameters[i-1];
            GetServerData getServerData = new GetServerData();
			getServerData.execute(execParams);



			try {
				algResult = getServerData.get(); //This can also return the word "Error"
			} catch (Exception e) {
				e.printStackTrace();
				serverAvailable = false;
				algResult = "Error";
			}
			if (algResult.equals("Error")) { //Unable to retrieve the data. URL may be invalid or the server may be down.
				serverAvailable = false;
			}
			else {
				try {
					realServerTime = Double.parseDouble(getElementValueFromXML(algResult, "runtime"));
					algResult = getElementValueFromXML(algResult, "result");

					// Analysis to csv file
					//writeCsvToFile();

				} catch (Exception e) {
					e.printStackTrace();
					algResult = "Error";
				}
			}
			overallTime = ((double) System.nanoTime()) / 1000000.0 - startTime;
			overallEnergy=P_tr*parametersSize/transferredBytesMs+P_i*(overallTime-ping-parametersSize/transferredBytesMs);



		}

		//In case of a failed offloading attempt, we don't retry offloading, we make the system behave like the decision would have been to not offload
		//(the main reason of failing is losing the network connection, which takes too long to recover)
		if (algResult.equals("Error")) {
			doOffloading = false;
			startTime = ((double) System.nanoTime()) / 1000000.0;
		}

		//Do not offload, execute locally in the Android mobile device
		if (!doOffloading) {algResult = Algorithms.executeLocally(algName, parameters);

		//This can be either the Android runtime or the total offloading time
		overallTime = ((double) System.nanoTime()) / 1000000.0 - startTime;
		overallEnergy=P_m*overallTime;}

		if (doOffloading && !algResult.equals("Error")) { //If offloading was done successfully
			if (parametersSize == 0) { //decide was not called
				for (int i = 0; parameters != null && i < parameters.length; i++) parametersSize += parameters[i].length();
			}
			double transferDataTime = overallTime - realServerTime - ping;
			//If the size of the sent parameters was big enough to be significant and the transferDataTime is also significant (bigger than 5 milliseconds), update the transferredBytesMs.
			if (parametersSize > 1024 && transferDataTime >= 5.0) {
				if (transferredBytesMsUpdatesCounter < 20) transferredBytesMsUpdatesCounter++;
				transferredBytesMs = transferredBytesMs * transferredBytesMsUpdatesCounter / (transferredBytesMsUpdatesCounter+1) + (parametersSize / transferDataTime) / (transferredBytesMsUpdatesCounter+1);
			}
		}

		//Updates the Csr and/or the costs DB when needed
		if (!doOffloading || !algResult.equals("Error")) {


            class UpdateCostCalcSystems implements Runnable {
                AlgName currentAlgName;
				boolean doOffloading;
				double estServerRuntime;
				double overallTime;
				double realServerTime;

                UpdateCostCalcSystems(AlgName algName, boolean doOffloading, double estServerRuntime, double overallTime, double realServerTime) {
					this.doOffloading = doOffloading;
					this.estServerRuntime = estServerRuntime;
					this.overallTime = overallTime;
					this.realServerTime = realServerTime;
                    this.currentAlgName = algName;
                }
                @Override
                public void run() {
                    updateCostCalcSystems(currentAlgName);
                }


				private void updateCostCalcSystems(AlgName algName) {

					if (!Algorithms.isAlgInCostsDB(algName)) {
					/*With the first system we only update the Csr when the algorithm has been executed locally and if we already
					calculated estServerRuntime; we don't want to calculate the estimated cost of this algorithm only for the
					purpose of updating the Csr as the calculations could be a bit expensive (although they shouldn't). More
					important, this case would occur when there is no network, and then any execution, even the heavy ones, would
					be done locally. We don't want to make the Csr fit with such cases, as Android gives more priority to heavy
					executions than to small ones (thus running proportionally faster the heavy ones); this would lead to a not
					consistent Csr. In the offloading decision scenario, when there is network connection, the heavy executions
					would always be offloaded. We check the time the algorithm took to execute to be greater than 15 ms (smaller
					values might be not accurate enough).*/
						if (!doOffloading && estServerRuntime != -1 && overallTime > MIN_RELEVANT_TIME) updateCsr(algName, (float) (overallTime/estServerRuntime)); //!doOffloading, so overallTime is an Android runtime
					}
					else {
					/*With the second system we always update the costs database. Even for heavy executions produced locally
					because of no network connection.*/
						if (!doOffloading) Algorithms.updateCostsDB(algName, overallTime, false);
						else Algorithms.updateCostsDB(algName, realServerTime, true);
					}

				}


            }

			if (executor == null) {
				executor = Executors.newFixedThreadPool(10);
			}
            executor.execute(new UpdateCostCalcSystems(algName, doOffloading, estServerRuntime, overallTime, realServerTime));
            //executor.


			//UpdateCostCalcSystemsThread updateCostCalcSystemsThread = new UpdateCostCalcSystemsThread(algName);
			//updateCostCalcSystemsThread.start();
		}

        if (serverAvailable == false) {
            ping = -1;
            transferredBytesMs = -1;

        }

        if (gui.isVerboseMode()) {
            verboseOutput();
        }
		collectData(algResult);

		return algResult;
	}

	/**
	 * writes offloading data to csv file for statistical analysis
	 * @param algResult	result of the offloading task
	 */

	private void collectData(String algResult) {
		String filename = "offloadingData.csv";
		File dataFile = new File(Environment.getExternalStorageDirectory(), filename);
		FileWriter fw;

        String decisionIndication;


		currentAlgID = Algorithms.getCurrentAlgInputRep();


        switch (this.decisionIndication) {
            case 0:
                decisionIndication = "Time Save";
                break;
            case 1:
                decisionIndication = "Energy Save";
                break;
            default:
                decisionIndication = "No Data";
        }


		try {
			fw = new FileWriter(dataFile, true);
			if (dataFile.length() == 0) {
				//dataFile.createNewFile();
				fw.write("GameID;" +
                                "InputID;" +
								"NetworkTyp;" +
								"ServerAvailable;" +
								"Ping;" +
								"CSR;" +
                                "DecisionIndication;" +
								"LocalTimeEstimation;" +
								"ServerTimeEstimation;" +
								"CommunicationTimeEstimatiom;" +
								"OverallOffloadingTimeEstimation;" +
                                "CommunicationEnergyEstimation;" +
                                "LocalEnergyEstimation;" +
                                "ServerEnergyEstimation;" +
								"RealServerTime;" +
								"RealExecutionTime;" +
								"OffloadingDone;" +
								"ExecutedTask;" +
								"ParamterSizeKB;" +
								"UplinkKBs;" +
								"Result" +
								"\n"
						);
				fw.flush();
			}

			fw.write(gameId + ";" +
                            currentAlgID + ";" +
					        connType + ";" +
							serverAvailable + ";" +
							ping + ";" +
							algCsrs.get(algName).csrServerDevice + ";" +
                            decisionIndication + ";" +
							estLocalTime + ";" +
							estServerRuntime + ";" +
							estCommunTime + ";" +
							estOffloadingTime + ";" +
                            estCommunEnergy + ";" +
                            estLocalEnergy + ";" +
                            estRemoteEnergy + ";" +
							realServerTime + ";" +
							overallTime + ";" +
							doOffloading + ";" +
							algName + ";" +
							parametersSize + ";" +
							transferredBytesMs + ";" +
							Serializer.deserialize(algResult) +
							"\n"
			);
			fw.close();

		} catch (IOException e) {
			e.printStackTrace();
		}


	}


	/**
	 * collect data for verbose output
	 */
	private void verboseOutput() {

        gui.runOnUIThread(new Runnable() {
			public void run() {
				gui.verboseOutput(estLocalTime,
						estServerRuntime,
						estOffloadingTime,
						estCommunTime,
						estLocalEnergy,
						estRemoteEnergy,
						estCommunEnergy,
						ping,
						parametersSize,
						transferredBytesMs,
						overallTime,
						algCsrs.get(algName).csrServerDevice,
						serverAvailable,
						doOffloading,
						algName,
                        realServerTime);
				}
			});
    }
	


    /*
     * execute can be called only with an algName and its parameters. By default, forceOffloading = false.
     */
	public String execute(AlgName algName, int decision_indicate, String... parameters) throws Search.StopSearch {
		return execute(false, algName, decision_indicate, parameters);
	}

	private void calcPingAndBandwidth() {
		pingCounter = 0;
		pingsArray = new double[10];
		timePingStart = ((double) System.nanoTime()) / 1000000.0;
		//The next will call itself recursively 10 times and calculate the average ping (removing outliers)
		//Once done, it will call calcTransferredBytesPerMs() to calculate the bandwidth quality
		new GetPing().execute(server);
	}

	private void calcTransferredBytesPerMs() throws Search.StopSearch {
		String fileContent = "";
		try {
			InputStream is = appContext.getAssets().open("fileToSend");
			byte[] buffer = new byte[is.available()];
			is.read(buffer);
			is.close();
			fileContent = new String(buffer);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		//This should update transferredBytesMs in almost all cases
		//If not, the value assigned in the Engine constructor function (200), will be used until the next update
		execute(true, AlgName.fileAndLoops, 0, Long.toString(0), fileContent);
	}

    //Persistent parameters
	private class CsrPair {
		public float csrServerDevice; //The computation speed relation between the server and the Android mobile device for a particular algorithm
		public float csrUpdatesCounter; //How many times this computation speed relation has been updated for a particular algorithm
	}

	private class NetworkReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			ConnectivityManager conn =  (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
			NetworkInfo networkInfo = conn.getActiveNetworkInfo();
			if (networkInfo != null && networkInfo.isConnected()) {
				if (connAvailable == false) calcPingAndBandwidth();
				connAvailable = true;
				int netType = networkInfo.getType();
				int netSubtype = networkInfo.getSubtype();
				if (netType == ConnectivityManager.TYPE_WIFI) connType = "Wi-Fi";
				else {
					if (netType == ConnectivityManager.TYPE_MOBILE  && netSubtype == TelephonyManager.NETWORK_TYPE_UMTS) connType = "3G";
					else if (netType == ConnectivityManager.TYPE_MOBILE  && netSubtype == TelephonyManager.NETWORK_TYPE_LTE) connType = "LTE";
					else if (netType == ConnectivityManager.TYPE_MOBILE  && netSubtype == TelephonyManager.NETWORK_TYPE_HSUPA) connType = "HSUPA";
					else if (netType == ConnectivityManager.TYPE_MOBILE  && netSubtype == TelephonyManager.NETWORK_TYPE_HSPAP) connType = "HSPAP";
					else if (netType == ConnectivityManager.TYPE_MOBILE  && netSubtype == TelephonyManager.NETWORK_TYPE_EDGE) connType = "EDGE";
					else if (netType == ConnectivityManager.TYPE_MOBILE  && netSubtype == TelephonyManager.NETWORK_TYPE_GPRS) connType = "GPRS";
					else
					connType = "Other";
				}
			}
			else {
				connType = "None";
				connAvailable = false;
				serverAvailable = false;
			}
		}

	}

	private class GetPing extends AsyncTask<String, Void, Integer> {

		@Override
		protected Integer doInBackground(String... urlAddress) {
			try {
				DefaultHttpClient httpClient = new DefaultHttpClient();
				HttpGet httpGet = new HttpGet(urlAddress[0]);
				ResponseHandler<String> resHandler = new BasicResponseHandler();
				httpClient.execute(httpGet, resHandler);
                httpClient.getConnectionManager().shutdown();
                return 0;
			} catch (Exception e) {
				e.printStackTrace();
				return -1;
			}
		}

		@Override
		protected void onPostExecute(Integer respCode) {
			if (respCode == -1) { //Ping failed
				serverAvailable = false;
			}
			else {
				pingsArray[pingCounter] = ((double) System.nanoTime()) / 1000000.0 - timePingStart;
				pingCounter++;
				if (pingCounter < 10) {
					timePingStart = ((double) System.nanoTime()) / 1000000.0;;
					new GetPing().execute(server);
				}
				else if (pingCounter == 10) {
					serverAvailable = true;
					ping = Engine.calcAverage(pingsArray);
					try {
						calcTransferredBytesPerMs();
					} catch (Search.StopSearch stopSearch) {
						stopSearch.printStackTrace();
					}
				}
			}
		}
	}

	private class GetServerData extends AsyncTask<String, Void, String> {

        protected void onPreExecute () {
			//Algorithms.getChessController().getGUI().setThinkingString("Offloading in progress...");
            Algorithms.getChessController().getGUI().runOnUIThread(new Runnable() {
                public void run() {
                    Algorithms.getChessController().getGUI().setThinkingString("Offloading Task: " + algName + "...");
				}
			});
        }


		@Override
		protected String doInBackground(String... execParams) {
			try {
				final HttpParams httpParams = new BasicHttpParams();
			    HttpConnectionParams.setConnectionTimeout(httpParams, 60000); //Wait max. 60 seconds to establish a TCP connection
			    HttpConnectionParams.setSoTimeout(httpParams, 70000); //Wait max. 70 seconds for a subsequent byte of data
				DefaultHttpClient httpClient = new DefaultHttpClient(httpParams);
				HttpPost httpPost = new HttpPost(server);
                List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
				nameValuePairs.add(new BasicNameValuePair("algName", execParams[0]));
				for (int i = 1; i < execParams.length; i++) {
                    nameValuePairs.add(new BasicNameValuePair("param" + i, execParams[i]));
                }
				UrlEncodedFormEntity entity = new UrlEncodedFormEntity(nameValuePairs);

                // size of data (byte):
                long sizeRequest = entity.getContentLength();


                httpPost.setEntity(entity);
                //ResponseHandler<String> resHandler = new BasicResponseHandler();
				HttpResponse responseData = httpClient.execute(httpPost);

				long sizeResponse = responseData.getEntity().getContentLength();
				transferedDataSize = sizeRequest + sizeResponse;


				String response = EntityUtils.toString(responseData.getEntity());
                httpClient.getConnectionManager().shutdown();
				return response;
			} catch (Exception e) {
				e.printStackTrace();
				return "Error";
			}
		}

        protected void onPostExecute(String result) {
            //Algorithms.getChessController().getGUI().setThinkingString("");
            Algorithms.getChessController().getGUI().runOnUIThread(new Runnable() {
                public void run() {
                    Algorithms.getChessController().getGUI().setThinkingString("");


                }
            });
        }



	}

}
