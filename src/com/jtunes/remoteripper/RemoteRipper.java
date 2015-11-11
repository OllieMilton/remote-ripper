package com.jtunes.remoteripper;

import com.jtunes.util.client.JTunesAddress;
import com.jtunes.util.client.RemoteClient;
import com.jtunes.util.client.RunnableClient;
import com.jtunes.util.domain.Device;
import com.jtunes.util.domain.DeviceConfig;
import com.jtunes.util.domain.DeviceConfigParam;
import com.jtunes.util.domain.DeviceStatus;
import com.jtunes.util.domain.DeviceType;

import oaxws.annotation.WebService;
import oaxws.annotation.WsMethod;
import oaxws.annotation.WsParam;
import oaxws.domain.WsSession;
import serialiser.factory.SerialiserFactory;

@RunnableClient
@WebService("remoteRipper")
public class RemoteRipper extends RemoteClient {

	private RipperStateMachine ripper;
	
	public RemoteRipper() {
		super(SerialiserFactory.getJsonSerialiser());
	}
	
	@Override
	protected void loggedIn(WsSession session) {
		ripper = new RipperStateMachine(this::sendStatus, session.getSessionId(), name);
		Device d = client.registerRemoteDevice(name, DeviceType.REMOTE_RIPPER);
		if (d != null) {
			String ripUploadAddress = getAddress(JTunesAddress.RIP_UPLOAD_ADDRESS);
			DeviceConfig cdrom = d.getConfigMap().get(DeviceConfigParam.CDROM_DRIVE);
			DeviceConfig tmpDir = d.getConfigMap().get(DeviceConfigParam.RIP_TMP_DIRECTORY);
			if (cdrom != null && tmpDir != null && ripUploadAddress != null) {
				ripper.start(cdrom.getValue(), tmpDir.getValue(), ripUploadAddress);
			} else {
				logger.error("One or more config parameters missing.");
				fatalError();
			}
		} else {
			logger.error("Could not get device.");
			fatalError();
		}
	}

	@Override
	protected void beforeStart() {
		wsManager.registerWebService(this);
	}

	@Override
	protected void onFatalError() {
		ripper.cancel();
	}

	@Override
	protected void beforeShutdown() {
		ripper.cancel();
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
	
	@WsMethod("doUpload")
	public void startUpload(String state) {
		ripper.startUpload();
	}

	@WsMethod("finalise")
	public void finalise() {
		ripper.finalise();
	}
}