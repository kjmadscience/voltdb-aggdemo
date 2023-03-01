package org.voltdb.aggdemo;

/* This file is part of VoltDB.
 * Copyright (C) 2008-2021 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import java.util.Random;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

import org.voltdb.VoltTable;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcCallException;

import org.voltdb.client.topics.VoltDBKafkaPartitioner;

/**
 * This generates mock CDRS that need to be aggregated. It also deliberately
 * introduces the same kind of mistakes we see in the real world, such as
 * duplicate records, missing records and late records.
 *
 */
public class MediationDataGenerator {

//	Client voltClient = null;
	Producer<Long, MediationMessage> producer = null;

	String hostnames;
	int userCount;
	int tpMs;
	int durationSeconds;

	int missingRatio;
	int dupRatio;
	int lateRatio;
	int dateis1970Ratio;

	int normalCDRCount = 0;
	int missingCount = 0;
	int dupCount = 0;
	int lateCount = 0;
	int normalCD = 0;
	int dateis1970Count = 0;

	HashMap<String, MediationSession> sessionMap = new HashMap<String, MediationSession>();
	ArrayList<MediationMessage> dupMessages = new ArrayList<MediationMessage>();
	ArrayList<MediationMessage> lateMessages = new ArrayList<MediationMessage>();

	long startMs;
	Random r = new Random();
	long sessionId = 0;

	long dupCheckTtlMinutes = 3600;

	/**
	 * Set this to false if you want to send CDRS to VoltDB directly..
	 */
	boolean useKafka = true;

	public MediationDataGenerator(String hostnames, int userCount, long dupCheckTtlMinutes, int tpMs,
			int durationSeconds, int missingRatio, int dupRatio, int lateRatio, int dateis1970Ratio) throws Exception {

		this.hostnames = hostnames;
		this.userCount = userCount;
		this.dupCheckTtlMinutes = dupCheckTtlMinutes;
		this.tpMs = tpMs;
		this.durationSeconds = durationSeconds;
		this.missingRatio = missingRatio;
		this.dupRatio = dupRatio;
		this.lateRatio = lateRatio;
		this.dateis1970Ratio = dateis1970Ratio;

		msg("hostnames=" + hostnames + ", users=" + userCount + ", tpMs=" + tpMs + ",durationSeconds="
				+ durationSeconds);
		msg("missingRatio=" + missingRatio + ", dupRatio=" + dupRatio + ", lateRatio=" + lateRatio + ", dateis1970Ratio="
				+ dateis1970Ratio);

		msg("Log into VoltDB's Kafka Broker");
		
		producer = connectToKafka(hostnames, "org.apache.kafka.common.serialization.LongSerializer",
				"org.voltdb.aggdemo.MediationMessageSerializer");

	//	msg("Log into VoltDB");
	//	voltClient = connectVoltDB(hostnames);

	}

	public void run(int offset) {

		long laststatstime = System.currentTimeMillis();
		startMs = System.currentTimeMillis();
		//
		// setDupcheckTTLMinutes();

		long currentMs = System.currentTimeMillis();
		int tpThisMs = 0;
		long recordCount = 0;
		long lastReportedRecordCount = 0;

		while (System.currentTimeMillis() < (startMs + (1000 * durationSeconds))) {

			recordCount++;

			String randomCallingNumber = "Num" + (r.nextInt(userCount) + offset);

			MediationSession ourSession = sessionMap.get(randomCallingNumber);

			if (ourSession == null) {
				ourSession = new MediationSession(randomCallingNumber, getRandomDestinationId(), + (offset + sessionId++), r);
				sessionMap.put(randomCallingNumber, ourSession);
			}

			MediationMessage nextCdr = ourSession.getNextCdr();

			// Now decide what to do. We could just send the CDR, but where's the fun in
			// that?

			if (missingRatio > 0 && r.nextInt(missingRatio) == 0) {
				// Let's just pretend this CDR never happened...
				missingCount++;
			} else if (dupRatio > 0 && r.nextInt(dupRatio) == 0) {

				// let's send it. Lot's of times...
				for (int i = 0; i < 2 + r.nextInt(10); i++) {
					send(nextCdr);
				}

				// Also add it to a list of dup messages to send again, later...
				dupMessages.add(nextCdr);
				dupCount++;

			} else if (lateRatio > 0 && r.nextInt(lateRatio) == 0) {

				// Add it to a list of late messages to send later...
				lateMessages.add(nextCdr);
				lateCount++;

			} else if (dateis1970Ratio > 0 && r.nextInt(dateis1970Ratio) == 0) {

				// Set date to Jan 1, 1970, and then send it...
				nextCdr.setRecordStartUTC(0);
				send(nextCdr);
				dateis1970Count++;

			} else {

				send(nextCdr);
				normalCDRCount++;

			}

			if (tpThisMs++ > tpMs) {

				// but sleep if we're moving too fast...
				while (currentMs == System.currentTimeMillis()) {
					try {
						Thread.sleep(0, 50000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}

				currentMs = System.currentTimeMillis();
				tpThisMs = 0;
			}

			if (lateMessages.size() > 100000 || dupMessages.size() > 100000) {
				sendRemainingMessages();
			}

			
			if (laststatstime + 10000 < System.currentTimeMillis()) {

				double recordsProcessed = recordCount - lastReportedRecordCount;
				double tps = 1000 * (recordsProcessed / (System.currentTimeMillis() - laststatstime));

				msg("Offset = " + offset +  " Record " + recordCount + " TPS=" + (long) tps);
				msg("Active Sessions: " + sessionMap.size());

				laststatstime = System.currentTimeMillis();
				lastReportedRecordCount = recordCount;

			//	printApplicationStats(voltClient,nextCdr);
			}

			
		}

		sendRemainingMessages();

		printStatus();

	}

	/**
	 * Update the table cdr_dupcheck and change how long we keep records for. We do
	 * this at the start of each run because users might want to play around with
	 * the parameter DUPCHECK_TTLMINUTES between runs.
	 */
/*	private void setDupcheckTTLMinutes() {
		try {

			// Default is one day
			long ttlMinutes = 1440;

			// See if param is set. If it is, update table DDL...
			ClientResponse cr = voltClient.callProcedure("@AdHoc",
					"SELECT parameter_value FROM mediation_parameters WHERE parameter_name = 'DUPCHECK_TTLMINUTES';");

			if (cr.getStatus() == ClientResponse.SUCCESS) {
				VoltTable paramTable = cr.getResults()[0];
				if (paramTable.advanceRow()) {
					ttlMinutes = paramTable.getLong("parameter_value");
					voltClient.callProcedure("@AdHoc", "alter table cdr_dupcheck alter USING TTL " + ttlMinutes
							+ " MINUTES ON COLUMN insert_date BATCH_SIZE 50000 MAX_FREQUENCY 200;");
				}
			}

		} catch (IOException | ProcCallException e1) {
			msg("Error:" + e1.getMessage());
		}
	}
*/
	/**
	 * Print general status info
	 */
	private void printStatus() {

		msg("normalCDRCount = " + normalCDRCount);
		msg("missingCount = " + missingCount);
		msg("dupCount = " + dupCount);
		msg("lateCount = " + lateCount);
		msg("dateis1970Count = " + dateis1970Count);

	}

	/**
	 * Send any messages in the late or duplicates queues. Note this is not rate
	 * limited, and may cause a latency spike
	 */
	private void sendRemainingMessages() {
		// Send late messages
		msg("sending " + lateMessages.size() + " late messages");

		while (lateMessages.size() > 0) {
			MediationMessage lateCDR = lateMessages.remove(0);
			send(lateCDR);
		}

		// Send dup messages
		msg("sending " + dupMessages.size() + " duplicate messages");
		while (dupMessages.size() > 0) {
			MediationMessage dupCDR = dupMessages.remove(0);
			send(dupCDR);
		}
	}

	/**
	 * Send a CDR to VoltDB via Kafka.
	 * 
	 * @param nextCdr
	 */
	private void send(MediationMessage nextCdr) {

		if (useKafka) {

			ComplainOnErrorKafkaCallback coekc = new ComplainOnErrorKafkaCallback();
			ProducerRecord<Long, MediationMessage> newRecord = new ProducerRecord<Long, MediationMessage>(
					"incoming_cdrs", nextCdr.getSessionId(), nextCdr);
			producer.send(newRecord, coekc);
		} else {
		//	sendViaVoltDB(nextCdr);
		}

	}

	/**
	 * Send CDR directly to VoltDB, in case you want to see if there's a difference.
	 * 
	 * @param nextCdr
	 */
/*	private void sendViaVoltDB(MediationMessage nextCdr) {

		if (voltClient != null) {
			try {
				ComplainOnErrorCallback coec = new ComplainOnErrorCallback();

				voltClient.callProcedure(coec, "HandleMediationCDR", nextCdr.getSessionId(),
						nextCdr.getSessionStartUTC(), nextCdr.getSeqno(), nextCdr.getCallingNumber(),
						nextCdr.getDestination(), nextCdr.getEventType(), nextCdr.getRecordStartUTC(),
						nextCdr.getRecordUsage());
			} catch (Exception e) {
				msg(e.getMessage());
			}
		}

	}
*/
	/**
	 * @return A random website.
	 */
	private String getRandomDestinationId() {

		if (r.nextInt(10) == 0) {
			return "www.nytimes.com";
		}

		if (r.nextInt(10) == 0) {
			return "www.cnn.com";
		}

		return "www.voltdb.com";
	}

	public static void main(String[] args) throws Exception {

		if (args.length != 9) {
            msg("Usage: MediationDataGenerator hostnames userCount tpMs durationSeconds missingRatio dupRatio lateRatio dateis1970Ratio offset");
            msg("where missingRatio, dupRatio, lateRatio and dateis1970Ratio are '1 in' ratios - i.e. 100 means 1%");
			System.exit(1);
		}

		String hostnames = args[0];
		int userCount = Integer.parseInt(args[1]);
		int tpMs = Integer.parseInt(args[2]);
		int durationSeconds = Integer.parseInt(args[3]);
		int missingRatio = Integer.parseInt(args[4]);
		int dupRatio = Integer.parseInt(args[5]);
		int lateRatio = Integer.parseInt(args[6]);
		int dateis1970Ratio = Integer.parseInt(args[7]);
		int offset = Integer.parseInt(args[8]);
		MediationDataGenerator a = new MediationDataGenerator(hostnames, userCount, durationSeconds, tpMs,
				durationSeconds, missingRatio, dupRatio, lateRatio, dateis1970Ratio);
		
		a.run(offset);

	}

	/**
	 * 
	 * Connect to VoltDB using native APIS
	 * 
	 * @param commaDelimitedHostnames
	 * @return
	 * @throws Exception
	 */
/*	private static Client connectVoltDB(String commaDelimitedHostnames) throws Exception {
		Client client = null;
		ClientConfig config = null;

		try {
			msg("Logging into VoltDB");

			config = new ClientConfig(); // "admin", "idontknow");
			config.setTopologyChangeAware(true);
			config.setReconnectOnConnectionLoss(true);

			client = ClientFactory.createClient(config);

			String[] hostnameArray = commaDelimitedHostnames.split(",");

			for (int i = 0; i < hostnameArray.length; i++) {
				msg("Connect to " + hostnameArray[i] + "...");
				try {
					client.createConnection(hostnameArray[i]);
				} catch (Exception e) {
					msg(e.getMessage());
				}
			}

			msg("Connected to VoltDB");

		} catch (Exception e) {
			e.printStackTrace();
			throw new Exception("VoltDB connection failed.." + e.getMessage(), e);
		}

		return client;

	}
*/
	/**
	 * Connect to VoltDB using Kafka APIS
	 * 
	 * @param commaDelimitedHostnames
	 * @param keySerializer
	 * @param valueSerializer
	 * @return A Kafka Producer for MediationMessages
	 * @throws Exception
	 */
	private static Producer<Long, MediationMessage> connectToKafka(String commaDelimitedHostnames, String keySerializer,
			String valueSerializer) throws Exception {

		String[] hostnameArray = commaDelimitedHostnames.split(",");

		StringBuffer kafkaBrokers = new StringBuffer();
		for (int i = 0; i < hostnameArray.length; i++) {
			kafkaBrokers.append(hostnameArray[i]);
	//		kafkaBrokers.append(":9092");

			if (i < (hostnameArray.length - 1)) {
				kafkaBrokers.append(',');
			}
		}

		Properties props = new Properties();
		props.put("bootstrap.servers", kafkaBrokers.toString());
		props.put("acks", "1");
		props.put("retries", 0);
		props.put("batch.size", 30000);
		props.put("linger.ms", 1);
		props.put("buffer.memory", 33554432);
		props.put("key.serializer", keySerializer);
		props.put("value.serializer", valueSerializer);
		// props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, VoltDBKafkaPartitioner.class.getName());

		Producer<Long, MediationMessage> newProducer = new KafkaProducer<>(props);

		msg("Connected to VoltDB via Kafka");

		return newProducer;

	}

	/**
	 * Print a formatted message.
	 * 
	 * @param message
	 */
	public static void msg(String message) {

		SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		Date now = new Date();
		String strDate = sdfDate.format(now);
		System.out.println(strDate + ":" + message);

	}

	/**
	 * Check VoltDB to see how things are going...
	 * 
	 * @param client
	 * @param nextCdr 
	 */
	public static void printApplicationStats(Client client, MediationMessage nextCdr) {
		try {

			msg("");
			msg("Latest Stats:");
			msg("");

			ClientResponse cr = client.callProcedure("ShowAggStatus__promBL");
			if (cr.getStatus() == ClientResponse.SUCCESS) {
				VoltTable[] resultsTables = cr.getResults();
				for (int i = 0; i < resultsTables.length; i++) {
					if (resultsTables[i].advanceRow()) {
						msg(resultsTables[i].toFormattedString());
					}

				}

			}

			cr = client.callProcedure("GetBySessionId", nextCdr.getSessionId(), new Date(nextCdr.getSessionStartUTC()));

			if (cr.getStatus() == ClientResponse.SUCCESS) {
				VoltTable[] resultsTables = cr.getResults();
				for (int i = 0; i < resultsTables.length; i++) {
					if (resultsTables[i].advanceRow()) {
						msg(resultsTables[i].toFormattedString());
					}

				}

			}

		} catch (IOException | ProcCallException e1) {
			msg("Error:" + e1.getMessage());
		}

	}

}
