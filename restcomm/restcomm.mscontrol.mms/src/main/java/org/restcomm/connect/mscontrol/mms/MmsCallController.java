/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2013, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.restcomm.connect.mscontrol.mms;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import jain.protocol.ip.mgcp.message.parms.ConnectionDescriptor;
import jain.protocol.ip.mgcp.message.parms.ConnectionIdentifier;
import jain.protocol.ip.mgcp.message.parms.ConnectionMode;
import org.apache.commons.configuration.Configuration;
import org.joda.time.DateTime;
import org.restcomm.connect.commons.dao.Sid;
import org.restcomm.connect.commons.fsm.FiniteStateMachine;
import org.restcomm.connect.commons.fsm.State;
import org.restcomm.connect.commons.fsm.Transition;
import org.restcomm.connect.commons.patterns.Observe;
import org.restcomm.connect.commons.patterns.Observing;
import org.restcomm.connect.commons.patterns.StopObserving;
import org.restcomm.connect.commons.util.WavUtils;
import org.restcomm.connect.dao.DaoManager;
import org.restcomm.connect.dao.RecordingsDao;
import org.restcomm.connect.dao.entities.MediaAttributes;
import org.restcomm.connect.dao.entities.Recording;
import org.restcomm.connect.mgcp.CloseConnection;
import org.restcomm.connect.mgcp.CloseLink;
import org.restcomm.connect.mgcp.ConnectionStateChanged;
import org.restcomm.connect.mgcp.CreateBridgeEndpoint;
import org.restcomm.connect.mgcp.CreateConnection;
import org.restcomm.connect.mgcp.CreateLink;
import org.restcomm.connect.mgcp.DestroyEndpoint;
import org.restcomm.connect.mgcp.DestroyLink;
import org.restcomm.connect.mgcp.EndpointState;
import org.restcomm.connect.mgcp.EndpointStateChanged;
import org.restcomm.connect.mgcp.GetMediaGatewayInfo;
import org.restcomm.connect.mgcp.InitializeConnection;
import org.restcomm.connect.mgcp.InitializeLink;
import org.restcomm.connect.mgcp.LinkStateChanged;
import org.restcomm.connect.mgcp.MediaGatewayInfo;
import org.restcomm.connect.mgcp.MediaGatewayResponse;
import org.restcomm.connect.mgcp.MediaResourceBrokerResponse;
import org.restcomm.connect.mgcp.MediaSession;
import org.restcomm.connect.mgcp.OpenConnection;
import org.restcomm.connect.mgcp.OpenLink;
import org.restcomm.connect.mgcp.UpdateConnection;
import org.restcomm.connect.mgcp.UpdateLink;
import org.restcomm.connect.mrb.api.GetMediaGateway;
import org.restcomm.connect.mscontrol.api.MediaServerController;
import org.restcomm.connect.mscontrol.api.messages.CloseMediaSession;
import org.restcomm.connect.mscontrol.api.messages.Collect;
import org.restcomm.connect.mscontrol.api.messages.CreateMediaSession;
import org.restcomm.connect.mscontrol.api.messages.JoinBridge;
import org.restcomm.connect.mscontrol.api.messages.JoinComplete;
import org.restcomm.connect.mscontrol.api.messages.JoinConference;
import org.restcomm.connect.mscontrol.api.messages.Leave;
import org.restcomm.connect.mscontrol.api.messages.Left;
import org.restcomm.connect.mscontrol.api.messages.MediaGroupStateChanged;
import org.restcomm.connect.mscontrol.api.messages.MediaServerControllerStateChanged;
import org.restcomm.connect.mscontrol.api.messages.MediaServerControllerStateChanged.MediaServerControllerState;
import org.restcomm.connect.mscontrol.api.messages.MediaSessionInfo;
import org.restcomm.connect.mscontrol.api.messages.Mute;
import org.restcomm.connect.mscontrol.api.messages.Play;
import org.restcomm.connect.mscontrol.api.messages.Record;
import org.restcomm.connect.mscontrol.api.messages.StartMediaGroup;
import org.restcomm.connect.mscontrol.api.messages.StartRecording;
import org.restcomm.connect.mscontrol.api.messages.Stop;
import org.restcomm.connect.mscontrol.api.messages.StopMediaGroup;
import org.restcomm.connect.mscontrol.api.messages.StopRecording;
import org.restcomm.connect.mscontrol.api.messages.Unmute;
import org.restcomm.connect.mscontrol.api.messages.UpdateMediaSession;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 * @author maria.farooq@telestax.com (Maria Farooq)
 *
 */
public class MmsCallController extends MediaServerController {

    private final LoggingAdapter logger = Logging.getLogger(getContext().system(), this);

    // Finite State Machine
    private final FiniteStateMachine fsm;
    private final State uninitialized;
    private final State acquiringMediaGateway;
    private final State acquiringMediaGatewayInfo;
    private final State acquiringMediaSession;
    private final State acquiringBridge;
    private final State creatingMediaGroup;
    private final State acquiringRemoteConnection;
    private final State initializingRemoteConnection;
    private final State openingRemoteConnection;
    private final State updatingRemoteConnection;
    private final State pending;
    private final State active;
    private final State muting;
    private final State unmuting;
    private final State acquiringInternalLink;
    private final State initializingInternalLink;
    private final State openingInternalLink;
    private final State updatingInternalLink;
    private final State closingInternalLink;
    private final State closingRemoteConnection;
    private final State stopping;
    private final State failed;
    private final State inactive;

    // Call runtime stuff
    private ActorRef call;
    private Sid callId;
    private String localSdp;
    private String remoteSdp;
    private String connectionMode;
    private boolean callOutbound;
    private boolean webrtc;

    // CallMediaGroup
    private ActorRef mediaGroup;
    private ActorRef bridge;
    private ActorRef outboundCallBridgeEndpoint;

    // MGCP runtime stuff
    //private final ActorRef mediaGateway;
    // TODO rename following variable to 'mediaGateway'
    private ActorRef mediaGateway;
    private final ActorRef mrb;
    private MediaGatewayInfo gatewayInfo;
    private MediaSession session;
    private ActorRef bridgeEndpoint;
    private ActorRef remoteConn;
    private ActorRef internalLink;
    private ActorRef internalLinkEndpoint;
    private ConnectionMode internalLinkMode;

    // Call Recording
    private Sid accountId;
    private Sid recordingSid;
    private URI recordingUri;
    private Boolean recording = false;
    private DateTime recordStarted;
    private DaoManager daoManager;
    private Boolean collecting = false;

    // Runtime Setting
    private Configuration runtimeSettings;

    // Observer pattern
    private final List<ActorRef> observers;

    private ConnectionIdentifier connectionIdentifier;

    //public MmsCallController(final List<ActorRef> mediaGateways, final Configuration configuration) {
    public MmsCallController(final ActorRef mrb) {
        super();
        final ActorRef source = self();

        // Initialize the states for the FSM.
        this.uninitialized = new State("uninitialized", null, null);
        this.acquiringMediaGateway = new State("acquiring media gateway from mrb", new AcquiringMediaGateway(source), null);
        this.acquiringMediaGatewayInfo = new State("acquiring media gateway info", new AcquiringMediaGatewayInfo(source), null);
        this.acquiringMediaSession = new State("acquiring media session", new AcquiringMediaSession(source), null);
        this.acquiringBridge = new State("acquiring media bridge", new AcquiringBridge(source), null);
        this.creatingMediaGroup = new State("creating media group", new CreatingMediaGroup(source), null);
        this.acquiringRemoteConnection = new State("acquiring connection", new AcquiringRemoteConnection(source), null);
        this.initializingRemoteConnection = new State("initializing connection", new InitializingRemoteConnection(source), null);
        this.openingRemoteConnection = new State("opening connection", new OpeningRemoteConnection(source), null);
        this.updatingRemoteConnection = new State("updating connection", new UpdatingRemoteConnection(source), null);
        this.pending = new State("pending", new Pending(source), null);
        this.active = new State("active", new Active(source), null);
        this.muting = new State("muting", new Muting(source), null);
        this.unmuting = new State("unmuting", new Unmuting(source), null);
        this.acquiringInternalLink = new State("acquiring link", new AcquiringInternalLink(source), null);
        this.initializingInternalLink = new State("initializing link", new InitializingInternalLink(source), null);
        this.openingInternalLink = new State("opening link", new OpeningInternalLink(source), null);
        this.updatingInternalLink = new State("updating link", new UpdatingInternalLink(source), null);
        this.closingInternalLink = new State("closing link", new EnterClosingInternalLink(source), new ExitClosingInternalLink(
                source));
        this.closingRemoteConnection = new State("closing connection", new ClosingRemoteConnection(source), null);
        this.stopping = new State("stopping", new Stopping(source));
        this.inactive = new State("inactive", new Inactive(source));
        this.failed = new State("failed", new Failed(source));

        // Transitions for the FSM.
        final Set<Transition> transitions = new HashSet<Transition>();
        transitions.add(new Transition(this.uninitialized, this.acquiringMediaGateway));
        transitions.add(new Transition(this.acquiringMediaGateway, this.acquiringMediaGatewayInfo));
        transitions.add(new Transition(this.uninitialized, this.closingRemoteConnection));
        transitions.add(new Transition(this.acquiringMediaGatewayInfo, this.acquiringMediaSession));
        transitions.add(new Transition(this.acquiringMediaSession, this.acquiringBridge));
        transitions.add(new Transition(this.acquiringMediaSession, this.stopping));
        transitions.add(new Transition(this.acquiringBridge, this.creatingMediaGroup));
        transitions.add(new Transition(this.acquiringBridge, this.stopping));
        transitions.add(new Transition(this.creatingMediaGroup, this.acquiringRemoteConnection));
        transitions.add(new Transition(this.creatingMediaGroup, this.stopping));
        transitions.add(new Transition(this.creatingMediaGroup, this.failed));
        transitions.add(new Transition(this.acquiringRemoteConnection, this.initializingRemoteConnection));
        transitions.add(new Transition(this.initializingRemoteConnection, this.openingRemoteConnection));
        transitions.add(new Transition(this.openingRemoteConnection, this.active));
        transitions.add(new Transition(this.openingRemoteConnection, this.failed));
        transitions.add(new Transition(this.openingRemoteConnection, this.pending));
        transitions.add(new Transition(this.active, this.muting));
        transitions.add(new Transition(this.active, this.unmuting));
        transitions.add(new Transition(this.active, this.updatingRemoteConnection));
        transitions.add(new Transition(this.active, this.stopping));
        transitions.add(new Transition(this.active, this.acquiringInternalLink));
        transitions.add(new Transition(this.active, this.closingInternalLink));
        transitions.add(new Transition(this.active, this.creatingMediaGroup));
        transitions.add(new Transition(this.pending, this.active));
        transitions.add(new Transition(this.pending, this.failed));
        transitions.add(new Transition(this.pending, this.updatingRemoteConnection));
        transitions.add(new Transition(this.pending, this.stopping));
        transitions.add(new Transition(this.muting, this.active));
        transitions.add(new Transition(this.muting, this.closingRemoteConnection));
        transitions.add(new Transition(this.unmuting, this.active));
        transitions.add(new Transition(this.unmuting, this.closingRemoteConnection));
        transitions.add(new Transition(this.updatingRemoteConnection, this.active));
        transitions.add(new Transition(this.updatingRemoteConnection, this.stopping));
        transitions.add(new Transition(this.updatingRemoteConnection, this.failed));
        transitions.add(new Transition(this.closingRemoteConnection, this.inactive));
        transitions.add(new Transition(this.closingRemoteConnection, this.closingInternalLink));
        transitions.add(new Transition(this.acquiringInternalLink, this.closingRemoteConnection));
        transitions.add(new Transition(this.acquiringInternalLink, this.initializingInternalLink));
        transitions.add(new Transition(this.initializingInternalLink, this.closingRemoteConnection));
        transitions.add(new Transition(this.initializingInternalLink, this.openingInternalLink));
        transitions.add(new Transition(this.openingInternalLink, this.stopping));
        transitions.add(new Transition(this.openingInternalLink, this.updatingInternalLink));
        transitions.add(new Transition(this.updatingInternalLink, this.stopping));
        transitions.add(new Transition(this.updatingInternalLink, this.closingInternalLink));
        transitions.add(new Transition(this.updatingInternalLink, this.active));
        transitions.add(new Transition(this.closingInternalLink, this.closingRemoteConnection));
        transitions.add(new Transition(this.closingInternalLink, this.active));
        transitions.add(new Transition(this.closingInternalLink, this.inactive));
        transitions.add(new Transition(this.stopping, this.inactive));
        transitions.add(new Transition(this.stopping, this.failed));


        // Initialize the FSM.
        this.fsm = new FiniteStateMachine(uninitialized, transitions);

        // MGCP runtime stuff
        this.mrb = mrb;

        // Call runtime stuff
        this.localSdp = "";
        this.remoteSdp = "";
        this.callOutbound = false;
        this.connectionMode = "inactive";
        this.webrtc = false;

        // Observers
        this.observers = new ArrayList<ActorRef>(1);
    }

    /**
     * Checks whether the actor is currently in a certain state.
     *
     * @param state The state to be checked
     * @return Returns true if the actor is currently in the state. Returns false otherwise.
     */
    private boolean is(State state) {
        return this.fsm.state().equals(state);
    }

    private void broadcast(final Object message) {
        if (!this.observers.isEmpty()) {
            final ActorRef self = self();
            for (ActorRef observer : observers) {
                observer.tell(message, self);
            }
        }
    }

    private ActorRef createMediaGroup(final Object message) {
        // No need to create new media group if current one is active
        if (this.mediaGroup != null && !this.mediaGroup.isTerminated()) {
            return this.mediaGroup;
        }

        final Props props = new Props(new UntypedActorFactory() {
            private static final long serialVersionUID = 1L;
            @Override
            public UntypedActor create() throws Exception {
                return new MgcpMediaGroup(mediaGateway, session, bridgeEndpoint);
            }
        });
        return getContext().actorOf(props);
    }

    private void startRecordingCall() throws Exception {
        if(logger.isInfoEnabled()) {
            logger.info("Start recording call");
        }
        String finishOnKey = "1234567890*#";
        int maxLength = 3600;
        int timeout = 5;

        this.recordStarted = DateTime.now();
        this.recording = true;

        // Tell media group to start recording
        Record record = new Record(recordingUri, timeout, maxLength, finishOnKey, MediaAttributes.MediaType.AUDIO_ONLY);
        this.mediaGroup.tell(record, null);
    }

    private void stopCollect(Stop message) throws Exception {
        if(logger.isInfoEnabled()) {
            logger.info("Stop DTMF collect");
        }
        if (this.mediaGroup != null) {
            // Tell media group to stop recording
            mediaGroup.tell(message, null);
        }
        collecting = false;
        }

    private void stopRecordingCall(Stop message) throws Exception {
        if(logger.isInfoEnabled()) {
            logger.info("Stop recording call");
        }
        if (this.mediaGroup != null) {
            // Tell media group to stop recording
            mediaGroup.tell(message, null);
            this.recording = false;

            if (message.createRecord() && recordingUri != null) {
                Double duration;
                try {
                    duration = WavUtils.getAudioDuration(recordingUri);
                } catch (UnsupportedAudioFileException | IOException e) {
                    logger.error("Could not measure recording duration: " + e.getMessage(), e);
                    duration = 0.0;
                }
                if (!duration.equals(0.0)) {
                    if (logger.isInfoEnabled()) {
                        logger.info("Call wraping up recording. File already exists, duration: " + duration);
                    }
                    final Recording.Builder builder = Recording.builder();
                    builder.setSid(recordingSid);
                    builder.setAccountSid(accountId);
                    builder.setCallSid(callId);
                    builder.setDuration(duration);
                    builder.setApiVersion(runtimeSettings.getString("api-version"));
                    StringBuilder buffer = new StringBuilder();
                    buffer.append("/").append(runtimeSettings.getString("api-version")).append("/Accounts/")
                            .append(accountId.toString());
                    buffer.append("/Recordings/").append(recordingSid.toString());
                    builder.setUri(URI.create(buffer.toString()));
                    final Recording recording = builder.build();
                    RecordingsDao recordsDao = daoManager.getRecordingsDao();
                    recordsDao.addRecording(recording, MediaAttributes.MediaType.AUDIO_ONLY);
                }
            } else {
                if(logger.isInfoEnabled()) {
                    logger.info("Call wraping up recording. File doesn't exist since duration is 0");
                }
            }
        } else if(logger.isInfoEnabled()) {
             logger.info("Tried to stop recording but group was null.");
        }

    }

    /*
     * EVENTS
     */
    @Override
    public void onReceive(Object message) throws Exception {
        final Class<?> klass = message.getClass();
        final ActorRef self = self();
        final ActorRef sender = sender();
        final State state = fsm.state();

        if(logger.isInfoEnabled()) {
            logger.info("********** MmsCallController "+ self.path()+" Current State: \"" + state.toString());
            logger.info("********** MmsCallController "+ self.path()+" Processing Message: \"" + klass.getName() + " sender : " + sender.path());
        }

        if (Observe.class.equals(klass)) {
            onObserve((Observe) message, self, sender);
        } else if (StopObserving.class.equals(klass)) {
            onStopObserving((StopObserving) message, self, sender);
        } else if (CreateMediaSession.class.equals(klass)) {
            onCreateMediaSession((CreateMediaSession) message, self, sender);
        } else if (CloseMediaSession.class.equals(klass)) {
            onCloseMediaSession((CloseMediaSession) message, self, sender);
        } else if (UpdateMediaSession.class.equals(klass)) {
            onUpdateMediaSession((UpdateMediaSession) message, self, sender);
        } else if (MediaGatewayResponse.class.equals(klass)) {
            onMediaGatewayResponse((MediaGatewayResponse<?>) message, self, sender);
        } else if (ConnectionStateChanged.class.equals(klass)) {
            onConnectionStateChanged((ConnectionStateChanged) message, self, sender);
        } else if (LinkStateChanged.class.equals(klass)) {
            onLinkStateChanged((LinkStateChanged) message, self, sender);
        } else if (Leave.class.equals(klass)) {
            onLeave((Leave) message, self, sender);
        } else if (Mute.class.equals(klass)) {
            onMute((Mute) message, self, sender);
        } else if (Unmute.class.equals(klass)) {
            onUnmute((Unmute) message, self, sender);
        } else if (StartRecording.class.equals(klass)) {
            onStartRecordingCall((StartRecording) message, self, sender);
        } else if (StopRecording.class.equals(klass)) {
            onStopRecordingCall((StopRecording) message, self, sender);
        } else if (Stop.class.equals(klass)) {
            onStop((Stop) message, self, sender);
        } else if (StopMediaGroup.class.equals(klass)) {
            onStopMediaGroup((StopMediaGroup) message, self, sender);
        } else if (JoinConference.class.equals(klass)) {
            onJoinConference((JoinConference) message, self, sender);
        } else if (JoinBridge.class.equals(klass)) {
            onJoinBridge((JoinBridge) message, self, sender);
        } else if (Leave.class.equals(klass)) {
            onLeave((Leave) message, self, sender);
        } else if (MediaGroupStateChanged.class.equals(klass)) {
            onMediaGroupStateChanged((MediaGroupStateChanged) message, self, sender);
        } else if (Record.class.equals(klass)) {
            onRecord((Record) message, self, sender);
        } else if (Play.class.equals(klass)) {
            onPlay((Play) message, self, sender);
        } else if (Collect.class.equals(klass)) {
            onCollect((Collect) message, self, sender);
        } else if (EndpointStateChanged.class.equals(klass)) {
            onEndpointStateChanged((EndpointStateChanged) message, self, sender);
        } else if (MediaResourceBrokerResponse.class.equals(klass)) {
            onMediaResourceBrokerResponse((MediaResourceBrokerResponse<?>) message, self, sender);
        }
    }

    private void onMediaResourceBrokerResponse(MediaResourceBrokerResponse<?> message, ActorRef self, ActorRef sender) throws Exception {
        this.mediaGateway = (ActorRef) message.get();
        fsm.transition(message, acquiringMediaGatewayInfo);

    }

    private void onObserve(Observe message, ActorRef self, ActorRef sender) {
        final ActorRef observer = message.observer();
        if (observer != null) {
            synchronized (this.observers) {
                this.observers.add(observer);
                observer.tell(new Observing(self), self);
            }
        }
    }

    private void onStopObserving(StopObserving message, ActorRef self, ActorRef sender) {
        final ActorRef observer = message.observer();
        if (observer != null) {
            this.observers.remove(observer);
        }
    }

    private void onCreateMediaSession(CreateMediaSession message, ActorRef self, ActorRef sender) throws Exception {
        this.call = sender;
        this.connectionMode = message.getConnectionMode();
        this.callOutbound = message.isOutbound();
        this.remoteSdp = message.getSessionDescription();
        this.webrtc = message.isWebrtc();
        this.callId = message.callSid();

        fsm.transition(message, acquiringMediaGateway);
    }

    private void onCloseMediaSession (CloseMediaSession message, ActorRef self, ActorRef sender) throws Exception {
        if (is(pending) || is(updatingRemoteConnection) || is(active) || is(acquiringInternalLink) || is(updatingInternalLink)
                || is(creatingMediaGroup) || is(acquiringBridge) || is(acquiringMediaSession)) {
            fsm.transition(message, stopping);
        }
    }

    private void onUpdateMediaSession(UpdateMediaSession message, ActorRef self, ActorRef sender) throws Exception {
        this.remoteSdp = message.getSessionDescription();
        this.fsm.transition(message, updatingRemoteConnection);
    }

    private void onMediaGatewayResponse(MediaGatewayResponse<?> message, ActorRef self, ActorRef sender) throws Exception {
        if (is(acquiringMediaGatewayInfo)) {
            fsm.transition(message, acquiringMediaSession);
        } else if (is(acquiringMediaSession)) {
            fsm.transition(message, acquiringBridge);
        } else if (is(acquiringBridge)) {
            this.bridgeEndpoint = (ActorRef) message.get();
            this.bridgeEndpoint.tell(new Observe(self), self);
            fsm.transition(message, creatingMediaGroup);
        } else if (is(acquiringRemoteConnection)) {
            fsm.transition(message, initializingRemoteConnection);
        } else if (is(acquiringInternalLink)) {
            fsm.transition(message, initializingInternalLink);
        }
    }

    private void onConnectionStateChanged(ConnectionStateChanged message, ActorRef self, ActorRef sender) throws Exception {
        switch (message.state()) {
            case CLOSED:
                if (is(initializingRemoteConnection)) {
                    fsm.transition(message, openingRemoteConnection);
                } else if (is(openingRemoteConnection)) {
                    fsm.transition(message, failed);
                } else if (is(muting) || is(unmuting)) {
                    fsm.transition(message, closingRemoteConnection);
                } else if (is(closingRemoteConnection)) {
                    remoteConn = null;
                    if (this.internalLink != null) {
                        fsm.transition(message, closingInternalLink);
                    } else {
                        fsm.transition(message, inactive);
                    }
                } else if (is(updatingRemoteConnection)) {
                    fsm.transition(message, failed);
                }
                break;

            case HALF_OPEN:
                fsm.transition(message, pending);
                break;

            case OPEN:
                fsm.transition(message, active);
                break;

            default:
                break;
        }
    }

    private void onLinkStateChanged(LinkStateChanged message, ActorRef self, ActorRef sender) throws Exception {
        switch (message.state()) {
            case CLOSED:
                if (is(initializingInternalLink)) {
                    fsm.transition(message, openingInternalLink);
                } else if (is(openingInternalLink)) {
                    fsm.transition(message, stopping);
                } else if (is(closingInternalLink)) {
                    if (remoteConn != null) {
                        fsm.transition(message, active);
                    } else {
                        fsm.transition(message, inactive);
                    }
                }
                break;

            case OPEN:
                if (is(openingInternalLink)) {
                    fsm.transition(message, updatingInternalLink);
                } else if (is(updatingInternalLink)) {
                    fsm.transition(message, active);
                }
                break;

            default:
                break;
        }
    }

    private void onMute(Mute message, ActorRef self, ActorRef sender) throws Exception {
        fsm.transition(message, muting);
    }

    private void onUnmute(Unmute message, ActorRef self, ActorRef sender) throws Exception {
        fsm.transition(message, unmuting);
    }

    private void onStartRecordingCall(StartRecording message, ActorRef self, ActorRef sender) throws Exception {
        if (runtimeSettings == null) {
            this.runtimeSettings = message.getRuntimeSetting();
        }

        if (daoManager == null) {
            daoManager = message.getDaoManager();
        }

        if (accountId == null) {
            accountId = message.getAccountId();
        }

        this.callId = message.getCallId();
        this.recordingSid = message.getRecordingSid();
        this.recordingUri = message.getRecordingUri();
        this.recording = true;
        startRecordingCall();
    }

    private void onStopRecordingCall(StopRecording message, ActorRef self, ActorRef sender) throws Exception {
        if (this.recording) {
            if (runtimeSettings == null) {
                this.runtimeSettings = message.getRuntimeSetting();
            }
            if (daoManager == null) {
                this.daoManager = message.getDaoManager();
            }
            if (accountId == null) {
                this.accountId = message.getAccountId();
            }

            onStop(new Stop(false), self, sender);
        }
    }

    private void onStop(Stop message, ActorRef self, ActorRef sender) throws Exception {
        if (this.recording) {
            stopRecordingCall(message);
        } else if (this.collecting) {
            stopCollect(message);
        }
    }

    private void onStopMediaGroup(StopMediaGroup message, ActorRef self, ActorRef sender) throws Exception {
        if (is(active) && this.mediaGroup != null) {
            this.mediaGroup.tell(new Stop(), sender);
        }
    }

    private void onLeave(Leave message, ActorRef self, ActorRef sender) throws Exception {
        if ((is(active) && internalLinkEndpoint != null) || is(updatingInternalLink)) {
            fsm.transition(message, closingInternalLink);
        }
    }

    private void onJoinBridge(JoinBridge message, ActorRef self, ActorRef sender) throws Exception {
        // Get bridge endpoint data
        this.bridge = sender;
        this.internalLinkEndpoint = (ActorRef) message.getEndpoint();
        this.internalLinkMode = message.getConnectionMode();

        // Start join operation
        this.fsm.transition(message, acquiringInternalLink);
    }

    private void onJoinConference(JoinConference message, ActorRef self, ActorRef sender) throws Exception {
        // Ask the remote media session controller for the bridge endpoint.
        //Why ??
        this.bridge = sender;
        //internalLinkEndpoint is basically conference endpoint.
        this.internalLinkEndpoint = (ActorRef) message.getEndpoint();
        this.internalLinkMode = message.getConnectionMode();

        // Start join operation
        this.fsm.transition(message, acquiringInternalLink);
    }

    private void onMediaGroupStateChanged(MediaGroupStateChanged message, ActorRef self, ActorRef sender) throws Exception {
        switch (message.state()) {
            case ACTIVE:
                if (is(creatingMediaGroup)) {
                    fsm.transition(message, acquiringRemoteConnection);
                }
                break;

            case INACTIVE:
                if (is(creatingMediaGroup)) {
                    fsm.transition(message, failed);
                } else if(is(stopping)) {
                    this.mediaGroup.tell(new StopObserving(self), self);
                    context().stop(mediaGroup);
                    this.mediaGroup = null;

                    if(this.mediaGroup == null && this.bridgeEndpoint == null) {
                        fsm.transition(message, inactive);
                    }
                }
                break;

            default:
                break;
        }
    }

    private void onRecord(Record message, ActorRef self, ActorRef sender) {
        if (is(active)) {
            this.recording = Boolean.TRUE;
            // Forward message to media group to handle
            this.mediaGroup.tell(message, sender);
        }
    }

    private void onPlay(Play message, ActorRef self, ActorRef sender) {
        if (is(active) || is(muting)) {
            // Forward message to media group to handle
            this.mediaGroup.tell(message, sender);
        }
    }

    private void onCollect(Collect message, ActorRef self, ActorRef sender) {
        if (is(active)) {
            // Forward message to media group to handle
            this.mediaGroup.tell(message, sender);
            this.collecting = true;
        }
    }

    private void onEndpointStateChanged(EndpointStateChanged message, ActorRef self, ActorRef sender) throws Exception {
        if (is(stopping)) {
            if (sender.equals(this.bridgeEndpoint) && (EndpointState.DESTROYED.equals(message.getState()) || EndpointState.FAILED.equals(message.getState()))) {
                if(EndpointState.FAILED.equals(message.getState()))
                    logger.error("Could not destroy endpoint on media server. corresponding actor path is: " + this.bridgeEndpoint.path());
                this.bridgeEndpoint.tell(new StopObserving(self), self);
                context().stop(bridgeEndpoint);
                bridgeEndpoint = null;

                if(this.mediaGroup == null && this.bridgeEndpoint == null) {
                    this.fsm.transition(message, inactive);
                }
            }
        }
    }

    /*
     * ACTIONS
     */
    private final class AcquiringMediaGateway extends AbstractAction {

        public AcquiringMediaGateway(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            mrb.tell(new GetMediaGateway(callId), self());
        }
    }

    private final class AcquiringMediaGatewayInfo extends AbstractAction {

        public AcquiringMediaGatewayInfo(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            mediaGateway.tell(new GetMediaGatewayInfo(), self());
        }
    }

    private final class AcquiringMediaSession extends AbstractAction {

        public AcquiringMediaSession(final ActorRef source) {
            super(source);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void execute(final Object message) throws Exception {
            final MediaGatewayResponse<MediaGatewayInfo> response = (MediaGatewayResponse<MediaGatewayInfo>) message;
            gatewayInfo = response.get();
            mediaGateway.tell(new org.restcomm.connect.mgcp.CreateMediaSession(), source);
        }
    }

    public final class AcquiringBridge extends AbstractAction {

        public AcquiringBridge(final ActorRef source) {
            super(source);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void execute(final Object message) throws Exception {
            final MediaGatewayResponse<MediaSession> response = (MediaGatewayResponse<MediaSession>) message;
            session = response.get();
            mediaGateway.tell(new CreateBridgeEndpoint(session), source);
        }
    }

    private final class AcquiringRemoteConnection extends AbstractAction {

        public AcquiringRemoteConnection(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            mediaGateway.tell(new CreateConnection(session), source);
        }
    }

    private final class InitializingRemoteConnection extends AbstractAction {

        public InitializingRemoteConnection(ActorRef source) {
            super(source);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void execute(final Object message) throws Exception {
            final MediaGatewayResponse<ActorRef> response = (MediaGatewayResponse<ActorRef>) message;
            remoteConn = response.get();
            remoteConn.tell(new Observe(source), source);
            remoteConn.tell(new InitializeConnection(bridgeEndpoint), source);
        }
    }

    private final class OpeningRemoteConnection extends AbstractAction {
        public OpeningRemoteConnection(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            OpenConnection open = null;
            if (callOutbound) {
                open = new OpenConnection(ConnectionMode.SendRecv, webrtc);
            } else {
                final ConnectionDescriptor descriptor = new ConnectionDescriptor(remoteSdp);
                open = new OpenConnection(descriptor, ConnectionMode.SendRecv, webrtc);
            }
            remoteConn.tell(open, source);
        }
    }

    private final class UpdatingRemoteConnection extends AbstractAction {
        public UpdatingRemoteConnection(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final ConnectionDescriptor descriptor = new ConnectionDescriptor(remoteSdp);
            final UpdateConnection update = new UpdateConnection(descriptor);
            remoteConn.tell(update, source);
        }
    }

    private final class Active extends AbstractAction {

        public Active(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            if (is(updatingInternalLink)) {
                call.tell(new JoinComplete(bridgeEndpoint, session.id(), connectionIdentifier), super.source);
            } else if (is(closingInternalLink)) {
                call.tell(new Left(), super.source);
            } else if (is(openingRemoteConnection) || is(updatingRemoteConnection)) {
                ConnectionStateChanged connState = (ConnectionStateChanged) message;
                connectionIdentifier = connState.connectionIdentifier();
                logger.info("connectionIdentifier: "+connectionIdentifier);
                localSdp = connState.descriptor().toString();
                MediaSessionInfo mediaSessionInfo = new MediaSessionInfo(gatewayInfo.useNat(), gatewayInfo.externalIP(),
                        localSdp, remoteSdp);
                broadcast(new MediaServerControllerStateChanged(MediaServerControllerState.ACTIVE, mediaSessionInfo));
            }
        }
    }

    private final class Pending extends AbstractAction {

        public Pending(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            ConnectionStateChanged connState = (ConnectionStateChanged) message;
            localSdp = connState.descriptor().toString();
            MediaSessionInfo mediaSessionInfo = new MediaSessionInfo(gatewayInfo.useNat(), gatewayInfo.externalIP(), localSdp,
                    remoteSdp);
            broadcast(new MediaServerControllerStateChanged(MediaServerControllerState.PENDING, mediaSessionInfo));
        }

    }

    private final class AcquiringInternalLink extends AbstractAction {

        public AcquiringInternalLink(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            mediaGateway.tell(new CreateLink(session), source);
        }

    }

    private final class InitializingInternalLink extends AbstractAction {

        public InitializingInternalLink(final ActorRef source) {
            super(source);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void execute(Object message) throws Exception {
            final MediaGatewayResponse<ActorRef> response = (MediaGatewayResponse<ActorRef>) message;

            if (self().path().toString().equalsIgnoreCase("akka://RestComm/user/$j")) {
                if(logger.isInfoEnabled()) {
                    logger.info("Initializing Internal Link for the Outbound call");
                }
            }

            if (bridgeEndpoint != null) {
                if(logger.isInfoEnabled()) {
                    logger.info("##################### $$ Bridge for Call " + self().path() + " is terminated: "
                        + bridgeEndpoint.isTerminated());
                }
                if (bridgeEndpoint.isTerminated()) {
                    // fsm.transition(message, acquiringMediaGatewayInfo);
                    // return;
                    if(logger.isInfoEnabled()) {
                        logger.info("##################### $$ Call :" + self().path() + " bridge is terminated.");
                    }
                    // final Timeout expires = new Timeout(Duration.create(60, TimeUnit.SECONDS));
                    // Future<Object> future = (Future<Object>) akka.pattern.Patterns.ask(gateway, new
                    // CreateBridgeEndpoint(session), expires);
                    // MediaGatewayResponse<ActorRef> futureResponse = (MediaGatewayResponse<ActorRef>) Await.result(future,
                    // Duration.create(10, TimeUnit.SECONDS));
                    // bridge = futureResponse.get();
                    // if (!bridge.isTerminated() && bridge != null) {
                    // logger.info("Bridge for call: "+self().path()+" acquired and is not terminated");
                    // } else {
                    // logger.info("Bridge endpoint for call: "+self().path()+" is still terminated or null");
                    // }
                }
            }
            // if (bridge == null || bridge.isTerminated()) {
            // System.out.println("##################### $$ Bridge for Call "+self().path()+" is null or terminated: "+bridge.isTerminated());
            // }
            internalLink = response.get();
            internalLink.tell(new Observe(source), source);
            internalLink.tell(new InitializeLink(bridgeEndpoint, internalLinkEndpoint), source);
        }

    }

    private final class OpeningInternalLink extends AbstractAction {

        public OpeningInternalLink(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(Object message) throws Exception {
            internalLink.tell(new OpenLink(internalLinkMode), source);
        }

    }

    private final class UpdatingInternalLink extends AbstractAction {

        public UpdatingInternalLink(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final UpdateLink update = new UpdateLink(ConnectionMode.SendRecv, UpdateLink.Type.PRIMARY);
            internalLink.tell(update, source);
        }

    }

    private final class CreatingMediaGroup extends AbstractAction {

        public CreatingMediaGroup(ActorRef source) {
            super(source);
        }

        @Override
        public void execute(Object message) throws Exception {
            // Always reuse current media group if active
            if (mediaGroup == null) {
                mediaGroup = createMediaGroup(message);

                // start monitoring state changes in the media group
                mediaGroup.tell(new Observe(super.source), super.source);

                // start the media group to enable media operations
                mediaGroup.tell(new StartMediaGroup(), super.source);
            }
        }

    }

    private final class Muting extends AbstractAction {

        public Muting(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final UpdateConnection update = new UpdateConnection(ConnectionMode.SendOnly);
            remoteConn.tell(update, source);
        }

    }

    private final class Unmuting extends AbstractAction {

        public Unmuting(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(Object message) throws Exception {
            final UpdateConnection update = new UpdateConnection(ConnectionMode.SendRecv);
            remoteConn.tell(update, source);
        }

    }

    private final class EnterClosingInternalLink extends AbstractAction {

        public EnterClosingInternalLink(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            internalLink.tell(new CloseLink(), source);
        }

    }

    private final class ExitClosingInternalLink extends AbstractAction {

        public ExitClosingInternalLink(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            mediaGateway.tell(new DestroyLink(internalLink), source);
            internalLink = null;
            internalLinkEndpoint = null;
            internalLinkMode = null;
        }

    }

    private class ClosingRemoteConnection extends AbstractAction {

        public ClosingRemoteConnection(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(Object message) throws Exception {
            if (remoteConn != null) {
                remoteConn.tell(new CloseConnection(), source);
            }
        }
    }

    private class Stopping extends AbstractAction {

        public Stopping(ActorRef source) {
            super(source);
        }

        @Override
        public void execute(Object message) throws Exception {
            if (mediaGroup != null) {
                // Stop the media group
                mediaGroup.tell(new StopMediaGroup(), super.source);
            }

            if (bridgeEndpoint != null) {
                // Stop bridge endpoint
                bridgeEndpoint.tell(new DestroyEndpoint(), super.source);
            }
        }

    }

    private abstract class FinalState extends AbstractAction {

        private final MediaServerControllerState state;

        public FinalState(ActorRef source, final MediaServerControllerState state) {
            super(source);
            this.state = state;
        }

        @Override
        public void execute(Object message) throws Exception {
            // Notify observers the controller has stopped
            broadcast(new MediaServerControllerStateChanged(state));
            if (mediaGroup != null) {
                // Stop the media group
                mediaGroup.tell(new StopMediaGroup(), super.source);
            }

            if (bridgeEndpoint != null) {
                // Stop bridge endpoint
                bridgeEndpoint.tell(new DestroyEndpoint(), super.source);
            }
        }
    }

        protected void cleanup() {
            if(logger.isInfoEnabled()) {
                logger.info("De-activating Call Controller");
            }

            if (mediaGroup != null) {
            mediaGroup.tell(new StopObserving(self()), self());
                mediaGroup.tell(new StopMediaGroup(), null);
            context().stop(mediaGroup);
                mediaGroup = null;
            }

            if (remoteConn != null) {
                // mediaGateway.tell(new DestroyConnection(remoteConn), source);
                context().stop(remoteConn);
                remoteConn = null;
            }

            if (internalLink != null) {
                // mediaGateway.tell(new DestroyLink(internalLink), source);
                context().stop(internalLink);
                internalLink = null;
            }

            if (bridgeEndpoint != null) {
                if(logger.isInfoEnabled()) {
                    logger.info("Call Controller: " + self().path() + " about to stop bridge endpoint: " + bridgeEndpoint.path());
                }

            mediaGateway.tell(new DestroyEndpoint(bridgeEndpoint), self());

                context().stop(bridgeEndpoint);
                bridgeEndpoint = null;
            }

            bridge = null;
            outboundCallBridgeEndpoint = null;
        }

    private final class Inactive extends FinalState {

        public Inactive(final ActorRef source) {
            super(source, MediaServerControllerState.INACTIVE);
        }

    }

    private final class Failed extends FinalState {

        public Failed(final ActorRef source){
            super(source, MediaServerControllerState.FAILED);
        }

    }

    @Override
    public void postStop() {
        // Cleanup resources
        cleanup();

        // Clean observers
        observers.clear();

        // Terminate actor
        getContext().stop(self());
    }
}
