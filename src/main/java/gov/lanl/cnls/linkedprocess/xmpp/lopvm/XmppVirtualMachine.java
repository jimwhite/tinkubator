package gov.lanl.cnls.linkedprocess.xmpp.lopvm;

import gov.lanl.cnls.linkedprocess.LinkedProcess;
import gov.lanl.cnls.linkedprocess.xmpp.XmppClient;
import gov.lanl.cnls.linkedprocess.xmpp.lopfarm.XmppFarm;


import org.apache.log4j.Logger;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.IQTypeFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.provider.ProviderManager;

/**
 * User: marko
 * Date: Jun 23, 2009
 * Time: 11:01:06 AM
 */
public class XmppVirtualMachine extends XmppClient {

    public static Logger LOGGER = LinkedProcess.getLogger(XmppVirtualMachine.class);
    public static String RESOURCE_PREFIX = "LoPVM/";

    public static enum VirtualMachinePresence { AVAILABLE, TOO_MANY_JOBS }
    protected XmppFarm farm;

    public XmppVirtualMachine(final String server, final int port, final String username, final String password, XmppFarm farm) {

        this.farm = farm;

        LOGGER.info("Starting LoP virtual machine");
        // Registering the types of IQ packets/stanzas the the Lop VM can respond to.
        ProviderManager pm = ProviderManager.getInstance();
        pm.addIQProvider(Evaluate.EVALUATE_TAGNAME, LinkedProcess.LOP_VM_NAMESPACE, new EvaluateProvider());
        pm.addIQProvider(Status.STATUS_TAGNAME, LinkedProcess.LOP_VM_NAMESPACE, new StatusProvider());
        pm.addIQProvider(Cancel.CANCEL_TAGNAME, LinkedProcess.LOP_VM_NAMESPACE, new CancelProvider());

        try {
            this.logon(server, port, username, password);
            this.initiateFeatures();
            //this.printClientStatistics();
        } catch (XMPPException e) {
            LOGGER.error("error: " + e);
            System.exit(1);
        }
        

        PacketFilter evalFilter = new AndFilter(new PacketTypeFilter(Evaluate.class), new IQTypeFilter(IQ.Type.GET));
        PacketFilter statusFilter = new AndFilter(new PacketTypeFilter(Status.class), new IQTypeFilter(IQ.Type.GET));
        PacketFilter cancelFilter = new AndFilter(new PacketTypeFilter(Cancel.class), new IQTypeFilter(IQ.Type.GET));

        connection.addPacketListener(new EvaluateListener(this), evalFilter);
        connection.addPacketListener(new StatusListener(this), statusFilter);
        connection.addPacketListener(new CancelListener(this), cancelFilter);


    }

    protected void logon(String server, int port, String username, String password) throws XMPPException {

        super.logon(server, port, username, password, RESOURCE_PREFIX);
        connection.sendPacket(this.createVMPresence(VirtualMachinePresence.AVAILABLE));
    }

    public XmppFarm getFarm() {
        return this.farm;
    }

    public final Presence createVMPresence(final VirtualMachinePresence type) {
        String statusMessage = "temp util engine enacted.";
        //String statusMessage = engine.getFactory().getLanguageName() + "(" + engine.getFactory().getLanguageVersion() + "):" + engine.getFactory().getEngineName() + "(" + engine.getFactory().getEngineVersion() + ")";
        if(type == VirtualMachinePresence.AVAILABLE) {
            return new Presence(Presence.Type.available, statusMessage, LinkedProcess.LOWEST_PRIORITY, Presence.Mode.available);
        } else  {
            return new Presence(Presence.Type.unavailable, statusMessage, LinkedProcess.LOWEST_PRIORITY, Presence.Mode.dnd);
        }
    }

}
