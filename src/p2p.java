import java.io.*;
import java.net.*;
import java.util.*;

// ------------------------------Data Structures------------------------------

class Peer {
	InetAddress addr;
	int pdpPort;
	String outStr;
	
	Peer (InetAddress ip, int port){
		addr = ip;
		pdpPort = port;
		outStr = new String(addr.getHostAddress() + ":" + pdpPort);
	}
	
	boolean equals(Peer p) {
		return (p.addr.equals(addr) && p.pdpPort == pdpPort);
	}
}

class Neighbor {
	InetAddress addr;
	int port;
	String outStr;
	Integer timer;
	Boolean stop;
	
	Socket socket;
	BufferedReader inStream;
	DataOutputStream outStream;
	
	Neighbor(Socket acc) {
		socket = acc;
		addr = socket.getInetAddress();
		port = socket.getPort();
		outStr = addr.getHostAddress() + ":" + port;
		try {
			inStream = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			outStream = new DataOutputStream(socket.getOutputStream());
		} catch(IOException e) {
			e.printStackTrace();
		};
		timer = 0;
		stop = false;
	}
	
	synchronized void close() {//throws IOException {
		stop = true;
		try {
			//inStream.close();
			//outStream.close();
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

// ------------------------------Threads------------------------------

class PdpThread extends Thread {
	@Override
	public void run(){
		int port;
		InetAddress addr = null;
		
		while (true) {
			byte[] receiveData = new byte[1024];
			DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
			synchronized (p2p.pdpSocket) {
				try {
					p2p.pdpSocket.receive(receivePacket);
				} catch (IOException e) {
					e.printStackTrace();
					return;
				}
			}
			String str = new String(receivePacket.getData()).trim();
			InetAddress fromAddr = receivePacket.getAddress();
			int fromPort = receivePacket.getPort();
			System.out.println("PDP> Message received: <" + str + ">");
			String header = str.substring(0,3);
			
			try {
				int separator = str.lastIndexOf(":");
				addr = InetAddress.getByName(str.substring(3, separator));
				port = Integer.valueOf(str.substring(separator + 1));
			} catch (NumberFormatException e) {
				e.printStackTrace();
				continue;
			} catch (UnknownHostException e) {
				e.printStackTrace();
				continue;
			}
			
			if(header.equals("PI:")) {
				Peer newPeer = new Peer(addr, port);
				if(p2p.dupPeer(newPeer)) {
					System.out.println("PDP> Debug: Duplicate Peer");
					continue;
				}
				msgBroadcast(receiveData);
				p2p.peers.add(newPeer);
				try {
					sendPong(addr, port);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			else if(header.equals("PO:")) {
				Peer fromPeer = new Peer(fromAddr, fromPort);
				if(p2p.dupPeer(fromPeer)) {
					System.out.println("PDP> Debug: From duplicate Peer");
					continue;
				}
				
				Peer newPeer = new Peer(addr, fromPort);
				p2p.peers.add(newPeer);
				
				if(p2p.neighbors.size() < 2) {
					String peerStr = addr.getHostAddress() + ":" + port;
					System.out.println("PDP> Establishing TCP connection with " + peerStr);
					try {
						Socket socket = new Socket(addr, port);
//						Socket socket = new Socket();
//						socket.bind(new InetSocketAddress(p2p.ntcpPort));
//						socket.connect(new InetSocketAddress(addr, port));
						Neighbor neighbor = new Neighbor(socket);
						p2p.neighbors.add(neighbor);
						NeighborThread neighborThread = new NeighborThread(neighbor);
						neighborThread.start();
					} catch (IOException e) {
						e.printStackTrace();
						System.out.println("PDP> Failed to establish TCP connection with " + peerStr);
					}
					System.out.println("PDP> TCP connection established with " + peerStr);
				}
			}
		}
	}
	
	void sendPong(InetAddress addr, int port) throws IOException{
		String thisPeerStr = p2p.ipAddr.getHostAddress() + ":" + p2p.ntcpPort;
		String peerStr = addr.getHostAddress() + ":" + port;
		
		String msg = new String("PO:" + thisPeerStr);
		System.out.println("PDP> Ready to send Pong message:<" + msg + ">.");
		
		byte[] sendData = msg.getBytes();
		DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, addr, port);
		synchronized (p2p.pdpSocket) {
			p2p.pdpSocket.send(sendPacket);
		}
		
		System.out.println("PDP> Pong message sent to " + peerStr);
	}
	
	void msgBroadcast(byte[] sendData) {
		for (Peer p : p2p.peers) {
			System.out.println("PDP> Broadcasting " + new String(sendData).trim() + " to " + p.outStr);
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, p.addr, p.pdpPort);
			try {
				synchronized (p2p.pdpSocket) {
					p2p.pdpSocket.send(sendPacket);
				}
			} catch (IOException e) {
				e.printStackTrace();
				System.out.println("PDP> Error: Failed to send the packet to " + p.outStr);
			}
		}
		System.out.println("PDP> Broadcasting finished.");
	}
}

class NeighborWelcomeThread extends Thread {
	@Override
	public void run() {
		while (true) {
			try {
				Socket newSocket = p2p.ntcpServerSocket.accept();
				Neighbor neighbor = new Neighbor(newSocket);
				p2p.neighbors.add(neighbor);
				System.out.println("PWT > Connection created with " + neighbor.outStr);
				Thread neighborThread = new NeighborThread(neighbor);
				neighborThread.start();
			} catch (IOException e) {
				// e.printStackTrace();
				break;
			}
		}
	}
}

class NeighborThread extends Thread {
	Neighbor conn;
	
	public NeighborThread (Neighbor c) {
		conn = c;
	}
	
	@Override
	public void run() {
		NeighborTimeOutThread timeOutThread = new NeighborTimeOutThread(conn);
		timeOutThread.start();
		
		while (!conn.stop) {
			try {
				String recvMsg = conn.inStream.readLine();
				if(recvMsg == null) {
					throw new IOException();
				}
				synchronized (conn.timer) {
					conn.timer = 0;
				}
				if(recvMsg.length() == 0) {
					System.out.println("NT> Heartbeat message received from " + conn.outStr + ".");
				}
			} catch (IOException e) {
				// e.printStackTrace();
				System.out.println("NT> Connection lost with " + conn.outStr + ".");
				synchronized (conn.stop) {
					conn.stop = true;
				}
			}
		}
		conn.close();
		p2p.neighbors.remove(conn);
	}
}

class NeighborTimeOutThread extends Thread {
	
	public static final int REFRESH_INTERVAL = 100;
	
	Neighbor conn;
	int sendTimer;
	
	public NeighborTimeOutThread (Neighbor c) {
		conn = c;
		sendTimer = 0;
	}
	
	@Override
	public void run() {
		while (!conn.stop) {
			if(conn.timer > p2p.HEARTBEAT_TIMEOUT) {
				System.out.println("NTT> Connection timeout with " + conn.outStr + ", closing connection.");
				synchronized (conn.stop) {
					conn.stop = true;
				}
			}

			if(sendTimer > p2p.HEARTBEAT_INTERVAL) {
				System.out.println("NTT> Sending heartbeat message to " + conn.outStr);
				synchronized (conn.outStream) {
					try {
						conn.outStream.writeBytes("\n");
					} catch (IOException ioe) {
						// ioe.printStackTrace();
						System.out.println("NTT> Failed to deliver the heartbeat message.");
						synchronized (conn.stop) {
							conn.stop = true;
						}
					}
				}
				sendTimer = 0;
			}
			
			try {
				sleep(REFRESH_INTERVAL);
			} catch (InterruptedException e) {};
			
			synchronized (conn.timer) {
				conn.timer += REFRESH_INTERVAL;
			}
			sendTimer += REFRESH_INTERVAL;
		}
	}
}

// ------------------------------Main class------------------------------

public class p2p {
	// Constants
	static final int PDP_WAIT = 3000;
	static final int HEARTBEAT_INTERVAL = 10000;
	static final int HEARTBEAT_TIMEOUT = 12000;
	
	// Address and ports
	static InetAddress ipAddr;							// Local IP address
	static String ipAddrStr;							// String format Local IP address
	static int pdpPort;									// Port for peer discovery protocol (PDP)
	static int ntcpPort;								// Port for neighboring TCP connection
	static int ftcpPort;								// Port for file transfer TCP connection
	
	// Sockets
	static ServerSocket ntcpServerSocket;				// Neighbor TCP welcome socket
	static DatagramSocket pdpSocket;    				// Peer Discovery Protocol UDP socket
	
	// Lists
	static ArrayList<Neighbor> neighbors;				// Neighboring peer connections (have TCP connections) 
	static ArrayList<Peer> peers;						// All known peers
	
//	// Flags
//	static Boolean stopAll;
	
	public static void main(String[] args) {
		System.out.println("Starting the peer...");
		
		neighbors = new ArrayList<Neighbor>();
		peers = new ArrayList<Peer>();
//		stopAll = false;
		
		if(args.length >= 3) {
			ntcpPort = Integer.valueOf(args[0]);
			ftcpPort = Integer.valueOf(args[1]);
			pdpPort = Integer.valueOf(args[2]);
			try {
				ipAddr = InetAddress.getLocalHost();
				ipAddrStr = ipAddr.getHostAddress();
			} catch (UnknownHostException e) {
				System.out.println(e);
			}
		}
		else {
			readConfigPeer();
		}

		System.out.println("Neighbor TCP port: " + ntcpPort);
		System.out.println("File transfer TCP port: " + ftcpPort);
		System.out.println("Peer Discovery UDP port: " + pdpPort);
		System.out.println("Local HostAddress: "+ ipAddrStr);
		System.out.println("Local host name: "+ ipAddr.getHostName());
		
		pdpInit();
		ntcpInit();
		
		Scanner scn = new Scanner(System.in);
		
		while (true) {
			String cmd = scn.next();
			if(cmd.equals("Connect")) {
				String addrStr = scn.next();
				int port = scn.nextInt();
				
				System.out.println("Received command <Connect " + addrStr + " " + port + ">.");
				
				try {
					InetAddress addr = InetAddress.getByName(addrStr);
					pdpSendMsg(addr, port);
				} catch (UnknownHostException e) {
					System.out.println(e);
				}
			}
//			else if (cmd.equals("Get")) {
//				
//			}
			else if (cmd.equals("Leave")) {
				peers.clear();
				for(Neighbor i : neighbors) {
					i.close();
				}
				neighbors.clear();
				System.out.println("All connection closed and all PDP history cleared.");
			}
			else if (cmd.equals("Exit")) {
				scn.close();
				System.exit(0);
			}
			else {
				System.out.println("Unrecognized command.");
			}
		}
	}
	
	public static void readConfigPeer(){
		String pwd = p2p.class.getResource("").getPath() + "config_peer.txt";
		try {
			Scanner scn = new Scanner(new File(pwd));
			String hostName = scn.next(); 
			ipAddr = InetAddress.getByName(hostName);
			ipAddrStr = ipAddr.getHostAddress();
			ntcpPort = scn.nextInt();
			ftcpPort  = scn.nextInt();
			pdpPort = scn.nextInt();
			scn.close();
			
		} catch (FileNotFoundException fnfe) {
			System.out.println(fnfe);
			System.out.println("Warning: Lacking config_peer.txt, using default settings.");
			ntcpPort = 52020;
			ftcpPort = 52021;
			pdpPort = 52022;
			try {
				ipAddr = InetAddress.getLocalHost();
				ipAddrStr = ipAddr.getHostAddress();
			} catch (UnknownHostException e) {};
		} catch (UnknownHostException e) {
			System.out.println(e);
		};
	}
	
	static void pdpInit(){
		System.out.println("Info: Starting peer discovery thread...");
		try {
			pdpSocket = new DatagramSocket(pdpPort);
			PdpThread PdpThread = new PdpThread(); 
			PdpThread.start();
			System.out.println("Info: Peer discovery protocol started.");
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("Error: Failed starting Peer discovery protocol.");
		}
	}
	
	static void ntcpInit() {
		System.out.println("Info: Starting neighbor welcome thread...");
		try {
			ntcpServerSocket = new ServerSocket(ntcpPort);
			NeighborWelcomeThread neighborWelcomeThread = new NeighborWelcomeThread(); 
			neighborWelcomeThread.start();
			System.out.println("Info: neighbor welcome thread started.");
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("Error: Failed starting neighbor welcome thread.");
		}
	}
	
	static void pdpSendMsg(InetAddress addr, int port) {
		String msg = new String("PI:" + ipAddrStr + ":" + pdpPort);
		System.out.println("Sending Ping message:<" + msg + "> to " + addr.getHostAddress() + ":" + port);
		byte[] sendData = msg.getBytes();
		DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, addr, port);
		try {
			pdpSocket.send(sendPacket);
			System.out.println("Ping message successfully sent.");
		} catch (IOException e) {
			System.out.println(e);
		}
    }
    
    static boolean dupPeer(Peer p){
		for(Peer i : peers) 
			if(i.equals(p)) 
				return true;
		return false;
    }
	
}

