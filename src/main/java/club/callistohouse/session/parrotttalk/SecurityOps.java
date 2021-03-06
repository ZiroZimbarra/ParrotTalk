/*******************************************************************************
 * The MIT License (MIT)
 *
 * Copyright (c) 2003, 2016 Robert Withers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ******************************************************************************
 * murmur/whisper would not be possible without the ideas, implementation, 
 * brilliance and passion of the Squeak/Pharo communities and the cryptography 
 * team, which are this software's foundation.
 *******************************************************************************/

package club.callistohouse.session.parrotttalk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import club.callistohouse.session.marshmuck.DiffieHellman;
import club.callistohouse.session.marshmuck.HmacSHA1;
import club.callistohouse.session.marshmuck.MessageLogger;
import club.callistohouse.session.multiprotocol_core.Frame;
import club.callistohouse.session.multiprotocol_core.MAC;
import club.callistohouse.session.multiprotocol_core.PhaseHeader;
import club.callistohouse.session.thunkstack_core.SendFramesBuffer;
import club.callistohouse.session.thunkstack_core.Thunk;
import club.callistohouse.session.thunkstack_core.ThunkStack;
import club.callistohouse.utils.ArrayUtil;

public class SecurityOps implements Cloneable {

	public static void writePublicKey(PublicKey publicKey, DataOutputStream outStream) throws IOException {
		byte[] bytes = publicKey.getEncoded();
		outStream.writeShort(bytes.length);
		outStream.write(bytes);
	}

	public static PublicKey readPublicKey(DataInputStream inStream) throws IOException {
		int length = inStream.readShort();
		byte[] bytes = new byte[length];
		inStream.read(bytes);
		X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(bytes);
		KeyFactory keyFactory = null;
		try {
			keyFactory = KeyFactory.getInstance("RSA");
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		try {
			return keyFactory.generatePublic(pubKeySpec);
		} catch (InvalidKeySpecException e) {
			e.printStackTrace();
			return null;
		}
	}

	private CipherThunkMaker cryptoProtocol;
	private boolean isIncoming = false;
	private DiffieHellman diffieHellman = DiffieHellman.defaultDiffieHellman();
	private MessageLogger messageLogger = new MessageLogger();
	private byte[] macBytes;
	IvParameterSpec sendIv = null;
	IvParameterSpec receiveIv = null;
	// byte[] pbePassword;
	SecretKeySpec secretKeySpec;
	public SessionAgentMap map;

	public SecurityOps(String proto) {
		this.cryptoProtocol = lookupCryptoProtocol(proto);
	}

	public SecurityOps(SessionAgentMap map) {
		this.map = map;
	}

	public SecurityOps clone() throws CloneNotSupportedException {
		return (SecurityOps) super.clone();
	}

	public SessionAgentMap getSessionAgentMap() { return map; }

	public void makeNullLogging() {
		messageLogger = new MessageLogger.NullMessageLogger();
	}

	public void addLocalFrame(Frame frame) {
		messageLogger.addLocalMessage(frame.getHeader().toByteArray());
	}

	public void addRemoteFrame(Frame frame) {
		messageLogger.addRemoteMessage(frame.getHeader().toByteArray());
	}

	public String getDefaultAlgorithm() {
		return "AESede";
	}

	public String getDefaultEncoder() {
		return "asn1der";
	}

	public String getCryptoProtocolString() {
		return cryptoProtocol.shortCryptoProtocol;
	}

	public void setCryptoProtocol(String proto) {
		this.cryptoProtocol = lookupCryptoProtocol(proto);
	}

	public String getFullCryptoProtocol() {
		return cryptoProtocol.fullCryptoProtocol;
	}

	public boolean isIncoming() {
		return isIncoming;
	}

	public void setIsIncoming(boolean isIncoming) {
		this.isIncoming = isIncoming;
	}

	private DiffieHellman getDiffieHellman() {
		return diffieHellman;
	}

	public MessageLogger getMessageLogger() {
		return messageLogger;
	}

	public HmacSHA1 getHmac() {
		return new HmacSHA1(macBytes);
	}

	public byte[] getLocalMessagesBytes() {
		return messageLogger.getLocalMessagesBytes();
	}

	public byte[] getRemoteMessagesBytes() {
		return messageLogger.getRemoteMessagesBytes();
	}

	public byte[] getDhParam() throws NoSuchAlgorithmException {
		return getDiffieHellman().sendMessage();
	}

	public void processOtherSideDhParam(byte[] otherSideDhParam, boolean isIncoming) throws NoSuchAlgorithmException {
		byte[] sharedKey = getDiffieHellman().receiveMessage(otherSideDhParam);
		try {
			generateMacKey(sharedKey);
			// generateIvSequences(cryptoProtocol.blockSize, sharedKey);
			// generatePBEPassword(sharedKey);
		} catch (GeneralSecurityException e) {
			e.printStackTrace();
		}
	}

	private void generateMacKey(byte[] sharedKey) throws NoSuchAlgorithmException {
		macBytes = md5Hash(ArrayUtil.concatAll(
				padAndHash(new byte[] { (byte)0xCC }, sharedKey),
				padAndHash(new byte[] { (byte)0xBB }, sharedKey),
				padAndHash(new byte[] { (byte)0xAA }, sharedKey),
				padAndHash(new byte[] { (byte)0x99 }, sharedKey)));
		macBytes = ArrayUtil.concatAll(macBytes, md5Hash(ArrayUtil.concatAll(
				padAndHash(new byte[] { (byte)0x88 }, sharedKey),
				padAndHash(new byte[] { 0x77 }, sharedKey),
				padAndHash(new byte[] { 0x66 }, sharedKey),
				padAndHash(new byte[] { 0x55 }, sharedKey))));
		macBytes = ArrayUtil.concatAll(macBytes, md5Hash(ArrayUtil.concatAll(
				padAndHash(new byte[] { 0x44 }, sharedKey),
				padAndHash(new byte[] { 0x33 }, sharedKey),
				padAndHash(new byte[] { 0x22 }, sharedKey),
				padAndHash(new byte[] { 0x11 }, sharedKey))));
	}

	public static byte[] md5Hash(byte[] sourceBytes) throws NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance("MD5");
		return md.digest(sourceBytes);
	}

	public static byte[] sha1Hash(byte[] sourceBytes) throws NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance("SHA1");
		return md.digest(sourceBytes);
	}

	public static byte[] padAndHash(byte[] padBytes, byte[] secret) throws NoSuchAlgorithmException {
		byte[] paddedBytes = new byte[16];
		Arrays.fill(paddedBytes, padBytes[0]);
		return MessageDigest.getInstance("MD5").digest(ArrayUtil.concatAll(paddedBytes, secret));
	}

	public static SecurityOps nullSecrets() {
		return new SecurityOps("null");
	}

	public CipherThunkMaker lookupCryptoProtocol(String proto) {
		return map.lookupCryptoProtocol(proto);
	}

	public List<String> getCryptoProtocols() {
		return map.getProtocolNames();
	}

	public EncoderThunkMaker lookupDataEncoder(String proto) {
		return map.lookupDataEncoder(proto);
	}

	public List<String> getDataEncoders() {
		return map.getDataEncoderNames();
	}


	public void installOn(Session session, ThunkStack stack, boolean incoming) throws IOException {
		ThunkStack poppedStack = stack.popStackUpTo(session); // session
		SendFramesBuffer buffer = (SendFramesBuffer) stack.pop();
		stack.push(makeImmigrationThunk(stack));
		stack.push(makeCipherThunk(stack, incoming));
		stack.push(makeCustomsThunk(stack));
		stack.push(makeEncoderThunk(stack, session.getFarKey()));
		stack.pushStack(poppedStack);
		List<Frame> bufferList = buffer.bufferList();
		for(Frame frame : bufferList) {
			stack.downcall(frame, session);
		}
	}

	private Thunk makeCustomsThunk(ThunkStack stack) {
		HmacSHA1 hmac = getHmac();
		return new Thunk(false) {
			public Object downThunk(Frame frame) {
				try {
					stack.propertyAtPut("WriteMAC", hmac.computeMAC((byte[]) frame.getPayload()));
				} catch (IOException e) {
					throw new RuntimeException("customs failed MAC verification");
				}
				return frame;
			}

			public Object upThunk(Frame frame) {
				try {
					if (!Arrays.equals(hmac.computeMAC((byte[]) frame.getPayload()), (byte[]) stack.propertyAt("ReadMAC"))) {
						throw new RuntimeException("customs failed MAC verification");
					}
				} catch (IOException e) {
					throw new RuntimeException("customs failed MAC verification");
				}
				return frame;
			}
		};
	}

	private Thunk makeImmigrationThunk(ThunkStack stack) {
		return new Thunk() {
			public Object downThunk(Frame frame) {
				return frame.toByteArray();
			}

			public Object upThunk(Frame frame) {
				stack.propertyAtPut("ReadMAC", ((MAC) frame.getHeader()).getMac());
				return frame.getPayload();
			}

			public PhaseHeader getHeader(Frame frame) {
				return new MAC((byte[]) stack.propertyAt("WriteMAC"));
			}
		};
	}

	private EncoderThunk makeEncoderThunk(ThunkStack stack, SessionIdentity farKey) {
		return map.buildEncoder(farKey).makeThunkOnFarKey(farKey);
	}

	private Thunk makeCipherThunk(ThunkStack stack, boolean incoming) {
		List<byte[]> secretKeyHolder = new ArrayList<byte[]>();
		secretKeyHolder.add(diffieHellman.getSharedKey());
		return map.buildProtocol().makeThunk(secretKeyHolder, incoming);
	}

	public void clearSensitiveInfo() {
		// TODO Auto-generated method stub

	}

	public String matchBestCryptoProtocol(List<String> cryptoProtocols) {
		List<String> list = map.getProtocolNames();
		for(int i = 0; i < list.size(); i++) {
			if(cryptoProtocols.contains(list.get(i))) {
				return list.get(i);
			}
		}
		return "";
	}

	public String matchBestDataEncoder(List<String> dataEncoders) {
		List<String> list = map.getDataEncoderNames();
		for(int i = 0; i < list.size(); i++) {
			if(dataEncoders.contains(list.get(i))) {
				return list.get(i);
			}
		}
		return "";
	}
}
