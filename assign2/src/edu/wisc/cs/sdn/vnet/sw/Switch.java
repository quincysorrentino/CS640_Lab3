package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.floodlightcontroller.packet.MACAddress;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{	
    /** Learned MAC timeout in milliseconds */
    private static final long TIMEOUT = 15000;

    /** MAC learning table */
    private static class Entry {
        Iface iface;
        long lastSeen;

        Entry(Iface iface, long time) {
            this.iface = iface;
            this.lastSeen = time;
        }
    }

    private Map<MACAddress, Entry> macTable = new HashMap<>();

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		/********************************************************************/
		/* TODO: Handle packets                                             */
		
        long now = System.currentTimeMillis();

        // Remove expired MAC entries
        Iterator<Map.Entry<MACAddress, Entry>> it = macTable.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MACAddress, Entry> e = it.next();
            if (now - e.getValue().lastSeen > TIMEOUT) {
                it.remove();
            }
        }

        // Learn source MAC address
        MACAddress src = etherPacket.getSourceMAC();
        macTable.put(src, new Entry(inIface, now));

        MACAddress dst = etherPacket.getDestinationMAC();

        // If broadcast, flood
        if (dst.isBroadcast()) {
            for (Iface iface : this.interfaces.values()) {
                if (iface != inIface) {
                    sendPacket(etherPacket, iface);
                }
            }
            return;
        }

        // If destination known, forward
        Entry entry = macTable.get(dst);
        if (entry != null) {
            Iface outIface = entry.iface;

            if (outIface != inIface) {
                sendPacket(etherPacket, outIface);
            }
            return;
        }

        // Otherwise flood (unknown destination)
        for (Iface iface : this.interfaces.values()) {
            if (iface != inIface) {
                sendPacket(etherPacket, iface);
            }
        }

		/********************************************************************/
	}
}