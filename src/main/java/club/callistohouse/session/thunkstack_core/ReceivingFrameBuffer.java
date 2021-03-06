package club.callistohouse.session.thunkstack_core;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import club.callistohouse.session.multiprotocol_core.Frame;
import club.callistohouse.session.multiprotocol_core.PhaseHeader;
import club.callistohouse.session.multiprotocol_core.RawData;
import club.callistohouse.utils.BufferStream;

public class ReceivingFrameBuffer extends ThunkLayer {

	private ThunkStack stack;
	private Frame readFrame;
	private BufferStream bufferStream = new BufferStream(0);

	public ReceivingFrameBuffer() { super(); }
	
	public void setStack(ThunkStack aStack) { stack = aStack;}
	protected boolean doesFrameEmbedding() { return true; }

	public void drainBuffer() {
		int frameSpecSize = Frame.specificationSize();
		int remainingFrameSize = 0;

		while((readFrame != null) || (bufferStream.size() >= frameSpecSize)) {
			if(readFrame == null) { readFrame = Frame.onFrameSpecification(bufferStream.next(Frame.specificationSize())); }
			remainingFrameSize = readFrame.getMessageSize() - Frame.specificationSize();
			if(remainingFrameSize < 0) { throw new RuntimeException("frame size failure"); }
			if(bufferStream.size() < remainingFrameSize) { return; }

			try {
				byte[] bytes = bufferStream.next(remainingFrameSize);
				readFrame.readRemainderFrom(new ByteArrayInputStream(bytes));
				if (readFrame.getFrameVersion() == 1) {
					stack.upcall(readFrame, this);
					readFrame = null;
				} else {
					throw new IOException("bad frame version");
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		readFrame = null;
		if(bufferStream.size() == 0) {bufferStream.reset(); }
	}

	protected PhaseHeader getHeader(Frame frame) { return new RawData(); }
	public Object upThunk(Frame frame) {
		bufferStream.nextPutAll((byte[]) frame.getPayload());
		drainBuffer();
		throw new ThunkFinishedException();
	}
}
