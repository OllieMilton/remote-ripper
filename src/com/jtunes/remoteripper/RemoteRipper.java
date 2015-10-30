package com.jtunes.remoteripper;

import com.jtunes.util.client.RemoteClient;
import com.jtunes.util.client.RunnableClient;
import com.jtunes.util.domain.DeviceStatus;

import serialiser.factory.SerialiserFactory;

@RunnableClient
public class RemoteRipper extends RemoteClient {

	public RemoteRipper() {
		super(SerialiserFactory.getJsonSerialiser());
	}
	
	@Override
	protected void loggedIn() {
		
	}

	@Override
	protected void beforeStart() {
		
	}

	@Override
	protected void onFatalError() {
		
	}

	@Override
	protected void beforeShutdown() {
		
	}

	@Override
	protected String version() {
		return "0.1";
	}

	@Override
	protected String serviceName() {
		return "RemoteRipper";
	}

	@Override
	protected DeviceStatus getStatus() {
		return null;
	}
}
