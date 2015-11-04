package com.jtunes.remoteripper;

import com.jtunes.util.client.RemoteClient;
import com.jtunes.util.client.RunnableClient;
import com.jtunes.util.domain.DeviceStatus;
import com.jtunes.util.domain.DeviceType;

import oaxws.annotation.WebService;
import oaxws.annotation.WsMethod;
import oaxws.annotation.WsParam;
import serialiser.factory.SerialiserFactory;

@RunnableClient
@WebService("remoteRipper")
public class RemoteRipper extends RemoteClient {

	private RipperStateMachine ripper;
	
	public RemoteRipper() {
		super(SerialiserFactory.getJsonSerialiser());
	}
	
	@Override
	protected void loggedIn() {
		ripper = new RipperStateMachine(this::sendStatus, name);
		client.registerRemoteDevice(name, DeviceType.REMOTE_RIPPER);
		ripper.start("");
	}

	@Override
	protected void beforeStart() {
		wsManager.registerWebService(this);
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
	
	@WsMethod("cancel")
	public void cancel() {
		ripper.cancel();
	}

}
