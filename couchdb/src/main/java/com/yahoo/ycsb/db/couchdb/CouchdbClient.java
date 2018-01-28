package com.yahoo.ycsb.db.couchdb;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.ektorp.CouchDbConnector;
import org.ektorp.DocumentNotFoundException;
import org.ektorp.UpdateConflictException;
import org.ektorp.ViewQuery;
import org.ektorp.ViewResult;
import org.ektorp.ViewResult.Row;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.Status;
import com.yahoo.ycsb.StringByteIterator;

import com.yahoo.ycsb.db.couchdb.LoadBalancedConnector;

/*
 * Copyright 2013 KU Leuven Research and Development - iMinds - Distrinet
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Administrative Contact: dnet-project-office@cs.kuleuven.be
 * Technical Contact: arnaud.schoonjans@student.kuleuven.be
 */
public class CouchdbClient extends DB{
	
	// Default configuration
	private static final String DEFAULT_DATABASE_NAME = "test";
	private static final int DEFAULT_COUCHDB_PORT_NUMBER = 5984;
	private static final String PROTOCOL = "http";
	private List<StringToStringMap> batchInsertList;
	private int batchSize = 10000;
	// Database connector
	private CouchDbConnector dbConnector;
	// Return codes
	private static final int OK = 0;
	private static final int UPDATE_CONFLICT = -2;
	private static final int DOC_NOT_FOUND = -3;
	private static final int JSON_PARSING_FAULT = -4;
	
	public CouchdbClient(){
		this.dbConnector = null;
	}

	// Constructor for testing purposes
	public CouchdbClient(List<URL> urls){
		if(urls == null)
			throw new IllegalArgumentException("urls is null");
		this.dbConnector = new LoadBalancedConnector(urls, DEFAULT_DATABASE_NAME);
	}
	
	private List<URL> getUrlsForHosts() throws DBException{
		List<URL> result = new ArrayList<URL>();
		String hosts = getProperties().getProperty("hosts");
		String[] differentHosts = hosts.split(",");
		for(String host: differentHosts){
			URL url = this.getUrlForHost(host);
			result.add(url);
		}
		return result;
	}
	
	private URL getUrlForHost(String host) throws DBException{
		String[] hostAndPort = host.split(":");
		try{
			if(hostAndPort.length == 1){
				return new URL(PROTOCOL, host, DEFAULT_COUCHDB_PORT_NUMBER, "");
			} 
			else{
				int portNumber = Integer.parseInt(hostAndPort[1]);
				return new URL(PROTOCOL, hostAndPort[0], portNumber, "");
			}
		} catch(MalformedURLException exc){
			throw new DBException("Invalid host specified");
		} catch(NumberFormatException exc){
			throw new DBException("Invalid port number specified");
		}
	}
	
	@Override
	public void init() throws DBException{
		List<URL> urls = getUrlsForHosts();
		batchInsertList = new ArrayList<>();
		this.dbConnector = new LoadBalancedConnector(urls, DEFAULT_DATABASE_NAME);
	}
	
	private StringToStringMap executeReadOperation(String key){
		try{
			return this.dbConnector.get(StringToStringMap.class, key);
		} catch(DocumentNotFoundException exc){
			return null;
		}
	}
	
	private Status executeWriteOperation(String key, StringToStringMap dataToWrite){
		try{
			dataToWrite.put("_id", key);
			this.dbConnector.create(dataToWrite);
		} catch(UpdateConflictException exc){
			return Status.BAD_REQUEST;
		}
		return Status.OK;
	}
	
	private Status executeDeleteOperation(StringToStringMap dataToDelete){
		try{
			this.dbConnector.delete(dataToDelete);
		} catch(UpdateConflictException exc){
			return Status.BAD_REQUEST;
		}
		return Status.OK;
	}
	
	private Status executeUpdateOperation(StringToStringMap dataToUpdate){
		try{
			this.dbConnector.update(dataToUpdate);
		} catch(UpdateConflictException exc){
			return Status.BAD_REQUEST;
		}
		return Status.OK;
	}
	
	private void copyRequestedFieldsToResultMap(Set<String> fields,
			StringToStringMap inputMap,
			Map<String, ByteIterator> result){
		for(String field: fields){
			ByteIterator value = inputMap.getAsByteIt(field);
			result.put(field, value);
		}
		ByteIterator _id = inputMap.getAsByteIt("_id");
		ByteIterator _rev = inputMap.getAsByteIt("_rev");
		result.put("_id",  _id);
		result.put("_rev", _rev);
	}
	
	private void copyAllFieldsToResultMap(StringToStringMap inputMap,
			Map<String, ByteIterator> result){
		for(String field: inputMap.keySet()){
			ByteIterator value = inputMap.getAsByteIt(field);
			result.put(field, value);
		}
	}
	
	// Table variable is not used => already contained in database connector
	@Override
	public Status read(String table, String key, Set<String> fields,
			Map<String, ByteIterator> result) {
		StringToStringMap queryResult = this.executeReadOperation(key);
		if(queryResult == null)
			return Status.NOT_FOUND;
		if(fields == null){
			this.copyAllFieldsToResultMap(queryResult, result);
		}else{
			this.copyRequestedFieldsToResultMap(fields, queryResult, result);
		}
		return Status.OK;
	}
	
	@Override
	public Status scan(String table, String startkey, int recordcount,
			Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
		ViewResult viewResult = this.executeView(startkey, recordcount);
		for(Row row: viewResult.getRows()){
			JSONObject jsonObj = this.parseAsJsonObject(row.getDoc());
			if(jsonObj == null)
				return Status.BAD_REQUEST;
			if(fields == null){
				@SuppressWarnings("unchecked")
				Set<String> requestedFields = jsonObj.keySet();
				result.add(this.getFieldsFromJsonObj(requestedFields, jsonObj));
			}else{
				result.add(this.getFieldsFromJsonObj(fields, jsonObj));
			}
		}
		return Status.OK;
	}
	
	private ViewResult executeView(String startKey, int amountOfRecords){
		ViewQuery query = new ViewQuery()
	      .viewName("_all_docs")
	      .startKey(startKey)
	      .limit(amountOfRecords)
	      .includeDocs(true);
		return this.dbConnector.queryView(query);
	}
	
	private JSONObject parseAsJsonObject(String stringToParse){
		JSONParser parser = new JSONParser();
		try {
			return (JSONObject) parser.parse(stringToParse);
		} catch (ParseException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	private HashMap<String, ByteIterator> getFieldsFromJsonObj(Set<String> fields, JSONObject jsonObj){
		HashMap<String, ByteIterator> result = new HashMap<String, ByteIterator>();
		for(String key: fields){
			String value = jsonObj.get(key).toString();
			result.put(key, new StringByteIterator(value));
		}
		return result;
	}
	
	// Table variable is not used => already contained in database connector
	@Override
	public Status update(String table, String key,
			Map<String, ByteIterator> values) {
		StringToStringMap queryResult = this.executeReadOperation(key);
		if(queryResult == null)
			return Status.NOT_FOUND;
		StringToStringMap updatedMap = this.updateFields(queryResult, values);
		return this.executeUpdateOperation(updatedMap);
	}

	private StringToStringMap updateFields(StringToStringMap toUpdate, 
							Map<String, ByteIterator> newValues){
		for(String updateField: newValues.keySet()){
			ByteIterator newValue = newValues.get(updateField);
			toUpdate.put(updateField, newValue);
		}
		return toUpdate;
	}
	
	// Table variable is not used => already contained in database connector
	@Override
	public Status insert(String table, String key,
			Map<String, ByteIterator> values) {
		StringToStringMap dataToInsert = new StringToStringMap(values);
		if (batchSize == 1) {
			return this.executeWriteOperation(key, dataToInsert);
		} else {
			dataToInsert.put("_id", key);
			batchInsertList.add(dataToInsert);
			if (batchInsertList.size() == batchSize) {
				Status status=  this.executeWriteOperation(key, batchInsertList);
				batchInsertList.clear();
				return status;
			}
		}
		return Status.OK;
	}

	private Status executeWriteOperation(String key, List<StringToStringMap> batchInsertList) {
		try{
			List<Object> objectList = new ArrayList<Object>(batchInsertList);
			this.dbConnector.executeBulk(objectList);
		} catch(UpdateConflictException exc){
			return Status.BAD_REQUEST;
		}
		return Status.OK;
	}

	// Table variable is not used => already contained in database connector
	@Override
	public Status delete(String table, String key) {
		StringToStringMap toDelete = this.executeReadOperation(key);
		if(toDelete == null)
			return Status.NOT_FOUND;
		return this.executeDeleteOperation(toDelete);
	}
	
}
