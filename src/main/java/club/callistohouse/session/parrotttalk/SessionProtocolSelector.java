package club.callistohouse.session.parrotttalk;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;

import org.apache.log4j.Logger;

import com.github.oxo42.stateless4j.StateMachine;
import com.github.oxo42.stateless4j.StateMachineConfig;
import com.github.oxo42.stateless4j.delegates.Action;

import club.callistohouse.session.marshmuck.State;
import club.callistohouse.session.marshmuck.Trigger;
import club.callistohouse.session.multiprotocol_core.DuplicateConnection;
import club.callistohouse.session.multiprotocol_core.Frame;
import club.callistohouse.session.multiprotocol_core.MAC;
import club.callistohouse.session.multiprotocol_core.MessageEnum;
import club.callistohouse.session.multiprotocol_core.NotMe;
import club.callistohouse.session.multiprotocol_core.PhaseHeader;
import club.callistohouse.session.multiprotocol_core.ProtocolAccepted;
import club.callistohouse.session.multiprotocol_core.ProtocolOffered;
import club.callistohouse.session.rendezvous_v3_6.GiveInfo;
import club.callistohouse.session.rendezvous_v3_6.Go;
import club.callistohouse.session.rendezvous_v3_6.GoToo;
import club.callistohouse.session.rendezvous_v3_6.IAm;
import club.callistohouse.session.rendezvous_v3_6.IWant;
import club.callistohouse.session.rendezvous_v3_6.ReplyInfo;
import club.callistohouse.session.rendezvous_v3_7.Hello_v3_7;
import club.callistohouse.session.thunkstack_core.ThunkFinishedException;
import club.callistohouse.session.thunkstack_core.ThunkLayer;
import club.callistohouse.session.thunkstack_core.ThunkStack;
import club.callistohouse.utils.ClassUtil;

public class SessionProtocolSelector extends ThunkLayer {
	public static Logger log = Logger.getLogger(SessionProtocolSelector.class);

	private ThunkStack stack; 
	private Session session; 
	private SecurityOps securityOps; 
	StateMachine<State, Trigger> stateMachine;
	boolean isIncoming = false;

	public SessionProtocolSelector(Session session, SessionAgentMap map) {
		super();
		this.session = session;
		this.securityOps = new SecurityOps(map);
    	this.stateMachine = new StateMachine<State, Trigger>(State.Initial, buildStateMachineConfig());
	}
	public void setStack(ThunkStack aStack) { stack = aStack;}
	public void call() {
		stateMachine.fire(Trigger.Calling);
	}

	public void answer() {
    	stateMachine.fire(Trigger.Answering); 
	}
	SessionIdentity getLocalIdentity() { return session.getNearKey(); }
	SessionIdentity getRemoteIdentity() { return session.getFarKey(); }

	public Object upThunk(Frame frame) {
		if(frame.getHeaderType() != MessageEnum.MAC_DATA.getCode()) {
//			log.debug("handling: " + frame.getHeader());
			handleHeader(frame.getHeader());
    		throw new RuntimeException("protocol message");
		} else {
			handleHeader((MAC)frame.getHeader());
		}
		return null;
	}

	synchronized void send(PhaseHeader header) throws IOException {
		stack.downcall(header.toFrame(), this); }

	void sendProtocolOffered() {
		PhaseHeader header = new ProtocolOffered("ParrotTalk-3.6", "ParrotTalk-3.6");
		securityOps.addLocalFrame(header.toFrame());
		stateMachine.fire(Trigger.ExpectProtocolAccepted);
		try {
			send(header);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	void sendProtocolAccepted() {
		PhaseHeader header = new ProtocolAccepted("ParrotTalk-3.6");
		securityOps.addLocalFrame(header.toFrame());
		stateMachine.fire(Trigger.ExpectIWant);
		try {
			send(header);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	void sendIWant() {
		PhaseHeader header = new IWant(getRemoteIdentity().getVatId());
		securityOps.addLocalFrame(header.toFrame());
		stateMachine.fire(Trigger.ExpectIAm);
		try {
			send(header);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	void sendHello_v3_7() {
		PhaseHeader header;
		try {
			header = new Hello_v3_7(getRemoteIdentity().getVatId(), getRemoteIdentity().getDomain(), getLocalIdentity().getPublicKey(), securityOps.getDataEncoders(), securityOps.getCryptoProtocols(), securityOps.getDhParam(), session.getNearKey().getSignatureBytes(securityOps.getLocalMessagesBytes()));
			securityOps.addLocalFrame(header.toFrame());
			stateMachine.fire(Trigger.ExpectIAm);
			send(header);
		} catch (NoSuchAlgorithmException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (SignatureException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	void sendDuplicateConnection() {
		stateMachine.fire(Trigger.Disconnect);
		try {
			send(new DuplicateConnection());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	void sendNotMe() {
		stateMachine.fire(Trigger.Disconnect);
		try {
			send(new NotMe());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void handleHeader(PhaseHeader header) { 
		if(ClassUtil.isAssignableFrom(header, ProtocolOffered.class)) {
			handleMessage((ProtocolOffered) header);
		} else if(ClassUtil.isAssignableFrom(header, ProtocolAccepted.class)) {
			handleMessage((ProtocolAccepted) header);
		} else if(ClassUtil.isAssignableFrom(header, IWant.class)) {
			handleMessage((IWant) header);
		} else if(ClassUtil.isAssignableFrom(header, Hello_v3_7.class)) {
			handleMessage((Hello_v3_7) header);
		} else if(ClassUtil.isAssignableFrom(header, DuplicateConnection.class)) {
			handleMessage((DuplicateConnection) header);
		} else if(ClassUtil.isAssignableFrom(header, NotMe.class)) {
			handleMessage((NotMe) header);
		} else {
			log.debug("session msg received not handled: " + header);
			throw new RuntimeException("session msg received not handled: " + header);
		}
		throw new ThunkFinishedException();
	}

	public void handleMessage(ProtocolOffered body) {
    	if(stateMachine.isInState(State.AnswerReceiveProtocolOffered)) {
    		securityOps.addRemoteFrame(body.toFrame());
    		stateMachine.fire(Trigger.ReceivedProtocolOffered);
    	} else {
    		log.debug("Terminal in wrong connection state for ReceivedProtocolOffered trigger; in state: " + stateMachine.getState() + "; expecting: AnswerReceiveProtocolOffered");
    		throw new RuntimeException("Terminal in wrong connection state for ReceivedProtocolOffered trigger; in state: " + stateMachine.getState() + "; expecting: AnswerReceiveProtocolOffered");
    	}
	}
	public void handleMessage(ProtocolAccepted body) {
    	if(stateMachine.isInState(State.CallReceiveProtocolAccepted)) {
    		securityOps.addRemoteFrame(body.toFrame());
    		stateMachine.fire(Trigger.ReceivedProtocolAccepted);
    	} else {
    		log.debug("Terminal in wrong connection state for ReceivedProtocolAccepted trigger; in state: " + stateMachine.getState() + "; expecting: CallReceiveProtocolAccepted");
    		throw new RuntimeException("Terminal in wrong connection state for ReceivedProtocolAccepted trigger; in state: " + stateMachine.getState() + "; expecting: CallReceiveProtocolAccepted");
    	}
	}

	public void handleMessage(IWant body) {
		securityOps.addRemoteFrame(body.toFrame());
    	if(stateMachine.isInState(State.StartupReceiveIWant)) {
    		isIncoming = true;
    		securityOps.setIsIncoming(true);
    		if(!body.getVatId().equals(getLocalIdentity().getVatId())) {
    			stateMachine.fire(Trigger.SendNotMe);
    		} else {
    			stateMachine.fire(Trigger.ReceivedIWant);
    		}
    	} else {
    		log.debug("Terminal in wrong connection state for IWant msg; in state: " + stateMachine.getState() + "; expecting: StartupReceiveIWant");
    		throw new RuntimeException("Terminal in wrong connection state for IWant msg; in state: " + stateMachine.getState() + "; expecting: StartupReceiveIWant");
    	}
	}
	public void handleMessage(Hello_v3_7 body) {
		securityOps.addRemoteFrame(body.toFrame());
    	if(stateMachine.isInState(State.StartupReceiveIWant)) {
    		isIncoming = true;
    		securityOps.setIsIncoming(true);
    		if(!body.getVatId().equals(getLocalIdentity().getVatId())) {
    			stateMachine.fire(Trigger.SendNotMe);
    		} else {
    			stateMachine.fire(Trigger.ReceivedIWant);
    		}
    	} else {
    		log.debug("Terminal in wrong connection state for IWant msg; in state: " + stateMachine.getState() + "; expecting: StartupReceiveIWant");
    		throw new RuntimeException("Terminal in wrong connection state for IWant msg; in state: " + stateMachine.getState() + "; expecting: StartupReceiveIWant");
    	}
	}
	private void startupSuccessful(boolean isIncoming2) throws IOException {
		securityOps.installOn(session, stack, isIncoming2);
		securityOps.clearSensitiveInfo();
	}
	public void handleMessage(NotMe body) {
	}
	public void handleMessage(DuplicateConnection body) {
	}

	public StateMachineConfig<State,Trigger> buildStateMachineConfig() {
		StateMachineConfig<State, Trigger> sessionConnectionConfig = new StateMachineConfig<State, Trigger>();

		sessionConnectionConfig.configure(State.Initial)
			.permit(Trigger.Calling, State.CallInProgress)
			.permit(Trigger.Answering, State.AnswerInProgress);
		sessionConnectionConfig.configure(State.EncryptedConnected)
			.permit(Trigger.Disconnect, State.Closed);
		sessionConnectionConfig.configure(State.Closed)
			.onEntry(new Action() {
				public void doIt() {
					session.stop();
				}});
		sessionConnectionConfig.configure(State.Startup)
			.permit(Trigger.SendBye, State.IdentifiedStartupSendingBye)
			.permit(Trigger.Disconnect, State.Closed);
		sessionConnectionConfig.configure(State.IdentifiedStartup)
			.permit(Trigger.SendBye, State.IdentifiedStartupSendingBye)
			.permit(Trigger.Disconnect, State.Closed);
		sessionConnectionConfig.configure(State.StartupSendingNotMe)
			.substateOf(State.Startup)
			.onEntry(new Action() {
				public void doIt() {
					sendNotMe();
				}});
		sessionConnectionConfig.configure(State.IdentifiedStartupSendingBye)
			.substateOf(State.IdentifiedStartup)
			.onEntry(new Action() {
				public void doIt() {
					stateMachine.fire(Trigger.Disconnect);
				}});

		/** 
		 * Calling states
		 */
		sessionConnectionConfig.configure(State.CallInProgress)
			.substateOf(State.Initial)
			.onEntry(new Action() {
				public void doIt() {
					sendProtocolOffered();
				}})
			.permit(Trigger.ExpectProtocolAccepted, State.CallReceiveProtocolAccepted);
		sessionConnectionConfig.configure(State.CallReceiveProtocolAccepted)
			.substateOf(State.CallInProgress)
			.permit(Trigger.ReceivedProtocolAccepted, State.StartupSendingIWant)
			.permit(Trigger.ReceivedIWant, State.Closed)
			.permit(Trigger.ReceivedHello, State.Closed);
		sessionConnectionConfig.configure(State.StartupSendingIWant)
			.substateOf(State.Startup)
			.onEntry(new Action() {
				public void doIt() {
					sendIWant();
				}})
			.permit(Trigger.ExpectIAm, State.StartupReceiveIAm);
		sessionConnectionConfig.configure(State.StartupReceiveIAm)
			.substateOf(State.Startup)
			.permit(Trigger.ReceivedIAm, State.StartupSendingGiveInfo);
		sessionConnectionConfig.configure(State.IdentifiedStartupConnecting)
			.substateOf(State.IdentifiedStartup)
			.onEntry(new Action() {
				public void doIt() {
					stateMachine.fire(Trigger.Connect);
				}})
			.permit(Trigger.Connect, State.EncryptedConnected);

		/** 
		 * Answering states
		 */
		sessionConnectionConfig.configure(State.AnswerInProgress)
			.substateOf(State.Initial)
			.onEntry(new Action() {
				public void doIt() {
					stateMachine.fire(Trigger.ExpectProtocolOffered);
				}})
			.permit(Trigger.ExpectProtocolOffered, State.AnswerReceiveProtocolOffered);
		sessionConnectionConfig.configure(State.AnswerReceiveProtocolOffered)
			.substateOf(State.AnswerInProgress)
			.permit(Trigger.ReceivedProtocolOffered, State.AnswerSendingProtocolAccepted)
			.permit(Trigger.ReceivedIWant, State.Closed)
			.permit(Trigger.ReceivedHello, State.Closed);
		sessionConnectionConfig.configure(State.AnswerSendingProtocolAccepted)
			.substateOf(State.AnswerInProgress)
			.onEntry(new Action() {
				public void doIt() {
					sendProtocolAccepted();
				}})
			.permit(Trigger.ExpectIWant, State.StartupReceiveIWant);
		sessionConnectionConfig.configure(State.StartupReceiveIWant)
			.substateOf(State.Startup)
			.permit(Trigger.SendNotMe, State.StartupSendingNotMe)
			.permit(Trigger.ReceivedIWant, State.StartupSendingIAm);

		return sessionConnectionConfig;
	}
}
