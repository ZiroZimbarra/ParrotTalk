package club.callistohouse.session.parrotttalk;

import java.io.IOException;

import club.callistohouse.session.multiprotocol_core.Encoded;
import club.callistohouse.session.multiprotocol_core.Frame;
import club.callistohouse.session.multiprotocol_core.RawData;
import club.callistohouse.session.thunkstack_core.ThunkLayer;
import club.callistohouse.session.thunkstack_core.ThunkRoot;

public abstract class EncoderThunk extends ThunkRoot implements Cloneable {
	protected String encoderName;

	public EncoderThunk(String encoderName) { this.encoderName = encoderName; }

	public String getEncoderName() { return encoderName; }

	public abstract Object serializeThunk(Object chunk) throws IOException;
	public abstract Object materializeThunk(Object chunk) throws IOException, ClassNotFoundException;
	protected boolean doesFrameEmbedding() { return true; }

	public void downcall(Frame frame) {
		try {
			frame.setPayload(serializeThunk(frame.getPayload()));
			frame.setHeader(new Encoded());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	public void upcall(Frame frame) {
		try {
			frame.setPayload(materializeThunk(frame.getPayload()));
			frame.setHeader(new RawData());
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public EncoderThunk makeThunkOnFarKey(SessionIdentity farKey) {
		try {
			return (EncoderThunk) clone();
		} catch (CloneNotSupportedException e) {
			e.printStackTrace();
		}
		return null;
	}
}
