import java.io.*;
import java.net.*;
import java.util.*;

// ------------------------------Data Structures------------------------------

class Peer {
	InetAddress addr;
	int pdpPort;
	int ntcpPort;
	
	Socket ntcpSocket;
	BufferedReader inStream;
	DataOutputStream outStream;
	
	Peer (InetAddress ip, int pdp_port){
		addr = ip;
		pdpPort = pdp_port;
		ntcpPort = -1;
	}
	
	Peer (InetAddress ip, int pdp_port, int ntcp_port){
		addr = ip;
		pdpPort = pdp_port;
		ntcpPort = ntcp_port;
	}
	
	boolean equals(Peer p) {
		return (p.addr.equals(addr) && p.pdpPort == pdpPort);
	}
}

class PeerConnection {
	Socket socket;
	InetAddress destIP;
	int destPort;
	BufferedReader inStream;
	DataOutputStream outStream;
	
	PeerConnection(Socket acc) {
		socket = acc;
		destIP = socket.getInetAddress();
		destPort = socket.getPort();
		try {
			inStream = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			outStream = new DataOutputStream(socket.getOutputStream());
		} catch(IOException e) {};
	}
	
	void close() throws IOException {
		inStream.close();
		outStream.close();
		socket.close();
	}
}

// ------------------------------Threads------------------------------

class PdpServerThread extends Thread {
	@Override
	public void run(){
		try {
			serverMain();
		} catch (Exception e) {
			System.out.println(e);
		};
	}
	
	void serverMain() throws Exception {
		int port;
		InetAddress addr = null;
		
		while (true) {
			byte[] receiveData = new byte[1024];
			DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
			synchronized (p2p.pdpSocket) {
				p2p.pdpSocket.receive(receivePacket);
			}
			String str = new String(receivePacket.getData()).trim();
			InetAddress fromAddr = receivePacket.getAddress();
			int fromPort = receivePacket.getPort();
			System.out.println("PDPSrv> Message received: <" + str + ">");
			String header = str.substring(0,3);
			
			int separator = str.lastIndexOf(":");
			addr = InetAddress.getByName(str.substring(3, separator));
			port = Integer.valueOf(str.substring(separator + 1));
			
			if(header.equals("PI:")) {
				Peer newPeer = new Peer(addr, port);
				if(checkDuplicate(newPeer)) {
					System.out.println("PDPSrv> Debug: Duplicate Peer");
					continue;
				}
				msgBroadcast(receiveData);
				p2p.peers.add(newPeer);
				sendPong(addr, port);
			}
			else if(header.equals("PO:")) {
				Peer fromPeer = new Peer(fromAddr, fromPort);
				if(checkDuplicate(fromPeer)) {
					System.out.println("PDPSrv> Debug: From duplicate Peer");
					continue;
				}
				Peer newPeer = new Peer(addr, fromPort, port);
				p2p.peers.add(newPeer);
			}
		}
	}
	
	boolean checkDuplicate(Peer p){
		for(Peer i : p2p.peers) 
			if(i.equals(p)) 
				return true;
		return false;
	}
	
	void sendPong(InetAddress addr, int port) throws Exception{
		String msg = new String("PO:" + p2p.ipAddr.getHostAddress() + ":" + p2p.ntcpPort);
		System.out.println("PDPSrv> Ready to send Pong message:<" + msg + ">.");
		byte[] sendData = msg.getBytes();
		DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, addr, port);
		synchronized(p2p.pdpSocket) {
			p2p.pdpSocket.send(sendPacket);
		}
		System.out.println("PDPSrv> Pong message sent to " + addr.getHostAddress() + ":" + port);
	}
	
	void msgBroadcast(byte[] sendData) throws Exception{
		for (Peer p : p2p.peers) {
			System.out.println("PDPSrv> Broadcasting " + new String(sendData).trim() + " to " + p.addr.getHostAddress() + ":" + p.pdpPort);
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, p.addr, p.pdpPort);
			synchronized (p2p.pdpSocket) {
				p2p.pdpSocket.send(sendPacket);
			}
		}
		System.out.println("PDPSrv> Broadcasting finished.");
	}
}


//class PeerWelcomeThread extends Thread {
//	@Override
//	public void run() {
//		
//		try {
//			p2p.ntcpServerSocket = new ServerSocket(52020);
//		}
//		catch (IOException e) {
//			System.out.println(e);
//			System.out.println("PWT > Error: Unable to create Welcome Socket.");
//		};
//		
//		System.out.println("PWT > Welcome Socket created.");
//		
//		try {
//			while(true) {
//				Socket newSocket = p2p.ntcpServerSocket.accept();
//				PeerConnection pc = new PeerConnection(newSocket);
//				p2p.connections.add(pc);
//				System.out.println("PWT > Connection created.");
//				Thread pt = new PeerThread(pc);
//				pt.start();
//			}
//		}
//		catch(IOException e) {
//			//System.out.println("Hey! What are you doing!");
//		}
//			BufferedReader inFromClient = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
//			DataOutputStream outToClient = new DataOutputStream(connectionSocket.getOutputStream());
//			
//			clientSentence = inFromClient.readLine();
//			capitalizedSentence = clientSentence.toUpperCase() + '\n';
//			System.out.println("Server > The result is " + capitalizedSentence);
//			outToClient.writeBytes(capitalizedSentence);
//			System.out.println("Server > Response sent.");
//			
//			inFromClient.close();
//			outToClient.close();
//			connectionSocket.close();
//			System.out.println("Server > Connection Socket closed.");
//	}
//}

//	public void server() throws IOException {
//		ServerSocket welcomeSocket = new ServerSocket(52020);
//		System.out.println("Server > Welcome Socket created.");
//		
//		while(true) {
//			Socket connectionSocket = welcomeSocket.accept();
//			System.out.println("Server > Connection created with client.");
//			BufferedReader inFromClient = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
//			DataOutputStream outToClient = new DataOutputStream(connectionSocket.getOutputStream());
//			
//			clientSentence = inFromClient.readLine();
//			capitalizedSentence = clientSentence.toUpperCase() + '\n';
//			System.out.println("Server > The result is " + capitalizedSentence);
//			outToClient.writeBytes(capitalizedSentence);
//			System.out.println("Server > Response sent.");
//			
//			inFromClient.close();
//			outToClient.close();
//			connectionSocket.close();
//			System.out.println("Server > Connection Socket closed.");
//		}
//	}
//}

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
	static DatagramSocket pdpSocket;    			// Peer Discovery Protocol server socket
	
	// Lists
	static ArrayList<PeerConnection> connections;		// Neighboring peer connections (have TCP connections) 
	static ArrayList<Peer> peers;						// All known peers
	
	// Variables
	static int neighborNum;
	
	public static void main(String[] args) {
		System.out.println("Starting the peer...");
		
		connections = new ArrayList<PeerConnection>();
		peers = new ArrayList<Peer>();
		neighborNum = 0;
		
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
		
		Scanner scn = new Scanner(System.in);
		
		while(true) {
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
//			else if (cmd == "Get") {
//				
//			}
//			else if (cmd == "Leave") {
//				
//			}
			else if (cmd == "Exit") {
				break;
			}
			else {
				System.out.println("Unrecognized command.");
			}
		}
		scn.close();
	}
	
	public static void readConfigPeer() {
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
			} catch (UnknownHostException e) {}
		} catch (UnknownHostException e) {
			System.out.println(e);
		};
	}
	
	static void pdpInit(){
		System.out.println("Info: Starting peer discovery thread...");
		try {
			pdpSocket = new DatagramSocket(pdpPort);
			PdpServerThread pdpServerThread = new PdpServerThread(); 
			pdpServerThread.start();
			System.out.println("Info: Peer discovery protocol started.");
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("Error: Failed starting Peer discovery protocol.");
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
		
		try {
			Thread.sleep(PDP_WAIT);
		} catch (InterruptedException e) {}
		
		
	}
	
}

