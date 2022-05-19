package org.mobicents.servlet.sip.example;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.sip.B2buaHelper;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.TooManyHopsException;

public class CallOnHold extends SipServlet {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	private static final String SERVICE_OPTIONS = " está a tentar entrar em contacto:\n"
												+ "1. Rejeitar a nova chamada\n"
												+ "2. Colocar em espera a chamada actual\n"
												+ "3. Iniciar conferência";
	
	private final String domain;
	private final List<String> subscribers;
	
	private Map<String, String> bindings;
	private Map<String, SipServletRequest> callsOnHold;
	private SipFactory factory;
	
	public CallOnHold() {
		
		super();
		
		this.domain = "acme.pt";
		this.subscribers = new ArrayList<String>(Arrays.asList("alice", "bob", "claire"));
		
		this.bindings = new ConcurrentHashMap<String, String>();
		this.callsOnHold = new ConcurrentHashMap<String, SipServletRequest>();
	}

	@Override
	public void init() throws ServletException {
		super.init();
		this.factory = (SipFactory) this.getServletContext().getAttribute(SIP_FACTORY);
		
		this.bindings.put("announcement@acme.pt", "sip:announcement@127.0.0.1:5080");
		this.bindings.put("conference@acme.pt", "sip:conference@127.0.0.1:5090");
	}

	@Override
	protected void doRegister(SipServletRequest req) throws ServletException, IOException {
		
		String aor = parseHeaderAor(req.getHeader("To")).get("aor");
		Map<String, String> contact = parseHeaderContact(req.getHeader("Contact"));
			
		if (contact.containsKey("expires") && contact.get("expires").equals("0")) {
			this.bindings.remove(aor);
			
		} else {
			this.bindings.put(aor, contact.get("contact"));
		}
		
		SipServletResponse resp = req.createResponse(SipServletResponse.SC_OK);
		resp.send();
		
	}
	
	@Override
	protected void doInvite(SipServletRequest req) throws ServletException, IOException {
		
		String to = parseHeaderAor(req.getHeader("To")).get("aor");
			
		SipServletResponse resp = req.createResponse(SipServletResponse.SC_TRYING);
		resp.send();
		resp = null;
		
		String contact = this.bindings.get(to);
		if (contact != null) {
			
			B2buaHelper b2b = req.getB2buaHelper();
			
			SipServletRequest linkedReq = b2b.createRequest(req, true, null);
			linkedReq.setRequestURI(this.factory.createURI(contact));
			linkedReq.send();
			
		} else {
			resp = req.createResponse(SipServletResponse.SC_NOT_FOUND);
		}
			
		if (resp != null) {
			resp.send();
		}
		
	}

	@Override
	protected void doErrorResponse(SipServletResponse resp) throws ServletException, IOException {
		
		Map<String, String> from = parseHeaderAor(resp.getHeader("From"));
		Map<String, String> to = parseHeaderAor(resp.getHeader("To"));
		
		if (resp.getStatus() == SipServletResponse.SC_BUSY_HERE && this.subscribers.contains(to.get("username"))) {
			
			SipServletRequest req = resp.getRequest();
			B2buaHelper b2b = req.getB2buaHelper();
			SipServletRequest linkedReq = b2b.getLinkedSipServletRequest(req);
			this.callsOnHold.put(to.get("aor"), linkedReq);
			
			SipServletRequest messageReq = this.factory.createRequest(this.factory.createApplicationSession(), "MESSAGE", 
					this.factory.createURI("sip:callonhold@acme.pt"), this.factory.createURI(this.bindings.get(to.get("aor"))));
			
			String content = from.get("username") + SERVICE_OPTIONS;
			messageReq.setContent(content, "text/plain");
			messageReq.send();
			
		} else {
			forwardResponse(resp);
			super.doErrorResponse(resp);
		}
		
	}

	@Override
	protected void doInfo(SipServletRequest req) throws ServletException, IOException {
		
		Map<String, String> subscriber = parseHeaderAor(req.getHeader("From"));
		int signal = -1;
		
		if (this.subscribers.contains(subscriber.get("username"))) {
		
			try {
				
				String content = new String(req.getRawContent(), StandardCharsets.UTF_8);
				
				Pattern pattern = Pattern.compile("Signal=(\\d)");
				Matcher matcher = pattern.matcher(content);
				matcher.find();
				
				signal = Integer.parseInt(matcher.group(1));
				
			} catch (Exception e) {
				SipServletResponse resp = req.createResponse(SipServletResponse.SC_BAD_REQUEST);
				resp.send();
				return;
			}
			
			switch (signal) {
			case 1: rejectIncomingCall(subscriber.get("aor")); break;
			case 2: putOnHold(subscriber.get("aor"), req); break;
			case 3: startConference(subscriber.get("aor"), req); break;
			default:;
			}
		
		}
		
		SipServletResponse resp = req.createResponse(SipServletResponse.SC_OK);
		resp.send();
		
	}

	@Override
	protected void doBye(SipServletRequest req) throws ServletException, IOException {
		
		forwardRequest(req);
		
		Map<String, String> to = parseHeaderAor(req.getHeader("To"));
		Map<String, String> from = parseHeaderAor(req.getHeader("From"));
		SipServletRequest holdedCall = null;
		
		if (this.callsOnHold.containsKey(to.get("aor"))) {
			holdedCall = this.callsOnHold.get((to.get("aor")));
			
		} else if (this.callsOnHold.containsKey(from.get("aor"))) {
			holdedCall = this.callsOnHold.get(from.get("aor"));
		}
		
		if (holdedCall != null) {
			
			holdedCall.createResponse(SipServletResponse.SC_OK).send();
			
			SipServletRequest referReq = holdedCall.getSession().createRequest("REFER");
			referReq.addHeader("Refer-To", "sip:" + parseHeaderAor(holdedCall.getHeader("From")).get("aor"));
			referReq.send();
			
		}
	}

	@Override
	protected void doRequest(SipServletRequest req) throws ServletException, IOException {
		
		try {
			
			Map<String, String> to = parseHeaderAor(req.getHeader("To"));
			Map<String, String> from = parseHeaderAor(req.getHeader("From"));
			
			String headerContact = req.getHeader("Contact");
			if (headerContact != null) {
				parseHeaderContact(headerContact);
			}
			
			if (to.get("domain").equals(this.domain) && from.get("domain").equals(this.domain)) {
				super.doRequest(req);
				
			} else {
				SipServletResponse resp = req.createResponse(SipServletResponse.SC_FORBIDDEN);
				resp.send();
			}
			
		} catch (Exception e) {
			SipServletResponse resp = req.createResponse(SipServletResponse.SC_BAD_REQUEST);
			resp.send();
		}
		
	}

	@Override
	protected void doResponse(SipServletResponse resp) throws ServletException, IOException {
		
		if (Math.abs(resp.getStatus() - SipServletResponse.SC_BAD_REQUEST) >= 100) {
			forwardResponse(resp);
		}
		
		super.doResponse(resp);
		
	}
	
	private void forwardRequest(SipServletRequest req) throws UnsupportedEncodingException, IOException {
		
		B2buaHelper b2b = req.getB2buaHelper();
		SipSession session = req.getSession();
		SipSession linkedSession = b2b.getLinkedSession(session);
		
		SipServletRequest linkedReq = b2b.createRequest(linkedSession, req, null);
		try {
			linkedReq.setContent(req.getContent(), req.getContentType());
		} catch (Exception e) {}
		linkedReq.send();
		
	}
	
	private void forwardResponse(SipServletResponse resp) throws UnsupportedEncodingException, IOException {
		
		SipServletRequest req = resp.getRequest();
		B2buaHelper b2b = req.getB2buaHelper();
		SipServletRequest linkedReq = b2b.getLinkedSipServletRequest(req);
		
		SipServletResponse linkedResp = linkedReq.createResponse(resp.getStatus(), resp.getReasonPhrase());
		try {
			linkedResp.setContent(resp.getContent(), resp.getContentType());
		} catch (Exception e) {}
		linkedResp.send();
		
		if (resp.getStatus() == SipServletResponse.SC_OK && linkedReq.getMethod().equals("INVITE")) {
			resp.createAck().send();
		}
		
	}
	
	private void rejectIncomingCall(String subscriber) throws IOException {
		
		SipServletRequest req = this.callsOnHold.remove(subscriber);
		SipServletResponse resp = req.createResponse(SipServletResponse.SC_BUSY_HERE);
		resp.send();
		
	}
	
	private void putOnHold(String subscriber, SipServletRequest req) throws ServletParseException, IOException, IllegalArgumentException, TooManyHopsException {
		
		B2buaHelper b2b = req.getB2buaHelper();
		SipSession session = req.getSession();
		SipSession linkedSession = b2b.getLinkedSession(session);
		SipServletRequest incomingCall = this.callsOnHold.remove(subscriber);
		SipServletRequest referReq;
		
		referReq = linkedSession.createRequest("REFER");
		referReq.addHeader("Refer-To", "sip:announcement@acme.pt");
		referReq.send();
		this.callsOnHold.put(subscriber, referReq);
		
		incomingCall.createResponse(SipServletResponse.SC_OK).send();
		
		referReq = session.createRequest("REFER");
		referReq.addHeader("Refer-To", "sip:" + parseHeaderAor(incomingCall.getHeader("From")).get("aor"));
		referReq.send();
		
	}
	
	private void startConference(String subscriber, SipServletRequest currentCall) throws IOException, ServletParseException {
		
		B2buaHelper b2b = currentCall.getB2buaHelper();
		SipSession session = currentCall.getSession();
		SipSession linkedSession = b2b.getLinkedSession(session);
		SipServletRequest referReq;
		
		referReq = session.createRequest("REFER");
		referReq.addHeader("Refer-To", "sip:conference@acme.pt");
		referReq.send();
		
		referReq = linkedSession.createRequest("REFER");
		referReq.addHeader("Refer-To", "sip:conference@acme.pt");
		referReq.send();
		
		referReq = this.callsOnHold.remove(subscriber).getSession().createRequest("REFER");
		referReq.addHeader("Refer-To", "sip:conference@acme.pt");
		referReq.setRequestURI(this.factory.createURI(this.bindings.get(parseHeaderAor(referReq.getHeader("To")).get("aor"))));
		referReq.send();
		
	}
	
	private static Map<String, String> parseHeaderAor(String headerAor) {
		
		Map<String, String> result = new HashMap<String, String>();
		
		Pattern pattern = Pattern.compile("<sip:(.+)@(.+)>");
		Matcher matcher = pattern.matcher(headerAor);
		matcher.find();
		
		result.put("username", matcher.group(1));
		result.put("domain", matcher.group(2));
		result.put("aor", matcher.group(1) + "@" + matcher.group(2));
			
		return result;
		
	}
	
	private static Map<String, String> parseHeaderContact(String headerContact) {
		
		Map<String, String> result = new HashMap<String, String>();
		
		Pattern pattern = Pattern.compile("<(sip:.+@\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+)>");
		Matcher matcher = pattern.matcher(headerContact);
		matcher.find();
		
		result.put("contact", matcher.group(1));
		
		if (headerContact.contains("expires")) {
			
			pattern = Pattern.compile("expires=(\\d+)");
			matcher = pattern.matcher(headerContact);
			matcher.find();
			result.put("expires", matcher.group(1));
		}
		
		return result;
		
	}
	
	
}
