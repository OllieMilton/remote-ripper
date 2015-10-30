package com.jtunes.remoteripper;

import com.jtunes.util.client.RemoteClient;
import com.jtunes.util.client.RunnableClient;
import com.jtunes.util.domain.DeviceStatus;

import oaxws.annotation.WebService;
import oaxws.annotation.WsMethod;
import oaxws.annotation.WsParam;
import serialiser.factory.SerialiserFactory;

@RunnableClient
@WebService("RemoteRipper")
public class RemoteRipper extends RemoteClient {

	private RipperStateMachine ripper;
	
	public RemoteRipper() {
		super(SerialiserFactory.getJsonSerialiser());
		ripper = new RipperStateMachine();
	}
	
	@Override
	protected void loggedIn() {
		ripper.start("");
	}

	@Override
	protected void beforeStart() {
		
	}

	@Override
	protected void onFatalError() {
		ripper.abort();
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
		return ripper.getStatus();
	}

	@Override
	protected void mainHook() {
		ripper.tick();
	}
	
	@WsMethod("doRip")
	public void startRip(@WsParam("trackNo") int trackNo, @WsParam("fileName") String fileName) {
		ripper.ripTrack(trackNo, fileName);
	}
}
