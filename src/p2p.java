import java.io.*;
import java.net.*;
import java.util.*;

// ------------------------------Data Types------------------------------

class Peer {
	InetAddress addr;
	int port;
	String outStr;
	
	Peer (InetAddress ipaddr, int portnum){
		addr = ipaddr;
		port = portnum;
		outStr = new String(addr.getHostAddress() + ":" + port);
	}
	
	@Override
	public boolean equals(Object obj) {
		if(obj instanceof Peer)
			return (((Peer)obj).addr.equals(addr) && ((Peer)obj).port == port);
		return false;
	}
}

class Neighbor {
	InetAddress addr;
	int port;
	String outStr;
	volatile Integer timer;
	volatile Boolean stop;
	
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
			inStream.close();
			outStream.close();
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

class Query {
	int id;
	String file;
	Neighbor sender;
	boolean replied;
	
	public Query(int qid, String qfile) {
		id = qid;
		file = qfile;
		replied = false;
	}
	
	@Override
	public boolean equals(Object obj) {
		if(obj instanceof Query)
			return (((Query)obj).id == id && ((Query)obj).file.equals(file));
		return false;
	}
}

// ------------------------------Threads------------------------------

class PdpThread extends Thread {
	@Override
	public void run(){
		int port;
		InetAddress addr = null;
		
		while (true) {
			byte[] recvData = new byte[1024];
			DatagramPacket recvPacket = new DatagramPacket(recvData, recvData.length);
			synchronized (p2p.pdpSocket) {
				try {
					p2p.pdpSocket.receive(recvPacket);
				} catch (IOException e) {
					e.printStackTrace();
					return;
				}
			}
			String recvMsg = new String(recvPacket.getData()).trim();
			InetAddress fromAddr = recvPacket.getAddress();
			int fromPort = recvPacket.getPort();
			System.out.println("PDP> Message received: <" + recvMsg + ">");
			String header = recvMsg.substring(0,3);
			
			try {
				int separator = recvMsg.lastIndexOf(":");
				addr = InetAddress.getByName(recvMsg.substring(3, separator));
				port = Integer.valueOf(recvMsg.substring(separator + 1));
			} catch (NumberFormatException e) {
				e.printStackTrace();
				continue;
			} catch (UnknownHostException e) {
				e.printStackTrace();
				continue;
			}
			
			if(header.equals("PI:")) {
				Peer newPeer = new Peer(addr, port);
				if(p2p.peers.contains(newPeer)) {
					System.out.println("PDP> Debug: Duplicate Peer");
					continue;
				}
				msgBroadcast(recvData);
				p2p.peers.add(newPeer);
				try {
					sendPong(addr, port);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			else if(header.equals("PO:")) {
				Peer fromPeer = new Peer(fromAddr, fromPort);
				if(p2p.peers.contains(fromPeer)) {
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
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, p.addr, p.port);
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
				System.out.println("NWT> TCP Connection created with " + neighbor.outStr);
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
				if(recvMsg == null) throw new IOException();
				synchronized (conn.timer) {
					conn.timer = 0;
				}
				
				if(recvMsg.length() == 0) {
//					System.out.println("NT> Heartbeat message received from " + conn.outStr + ".");
				}
				else {
					System.out.println("NT> Message recieved from " + conn.outStr + ";");
					int colon = recvMsg.indexOf(":");
					String header = recvMsg.substring(0, colon);
					int semicolon = recvMsg.indexOf(";");
					int qid = Integer.valueOf(recvMsg.substring(colon + 1, semicolon));
					
					if(header.equals("Q")) {
						System.out.println("NT> Query message:<" + recvMsg + ">.");
						String filename = recvMsg.substring(semicolon + 1);
						Query q = new Query(qid, filename);
						if(p2p.queryFile.equals(filename) && p2p.queryID == qid) {
							System.out.println("NT> Duplicate query from this peer. ");
						}
						else if(p2p.files.contains(filename)) {
							System.out.println("NT> File found, sending response...");
							String reply = "R:" + qid + ";" + p2p.ipAddrStr + ":" + p2p.ftcpPort + ";" + filename + "\n";
							synchronized (conn.outStream) {
								conn.outStream.writeBytes(reply);
							}
							System.out.println("NT> Response sent to " + conn.outStr + ".");
						}
						else synchronized (p2p.queries) {
							if(p2p.queries.contains(q)) {
								System.out.println("NT> File not found & duplicate query.");
//								int i = p2p.queries.indexOf(q);
//								Query thisQuery = p2p.queries.get(i);
//								if(!thisQuery.senders.contains(conn))
//									thisQuery.senders.add(conn);
							}
							else {
								System.out.println("NT> File not found, forwarding to neighbors...");
								Query newQuery = new Query(qid, filename);
//								newQuery.senders.add(conn);
								newQuery.sender = conn;
								p2p.queries.add(newQuery);
								QueryTimeOutThread qtot = new QueryTimeOutThread(newQuery);
								qtot.start();
								
								for(Neighbor n : p2p.neighbors) {
									if(n == conn) continue;
									try {
										synchronized (n.outStream) {
											n.outStream.writeBytes(recvMsg + "\n");
										}
										System.out.println("NT> Query forwarded to " + n.outStr + ".");
									} catch (IOException e) {
										e.printStackTrace();
										System.out.println("NT> Failed forwarding query to " + n.outStr + ".");
									}
								}
							}
						}
					}
					else if(header.equals("R")) {
						System.out.println("NT> Response message:<" + recvMsg + ">.");
						int colon2 = recvMsg.lastIndexOf(":");
						int semicolon2 = recvMsg.lastIndexOf(";");
						String addrStr = recvMsg.substring(semicolon + 1, colon2);
						String portStr = recvMsg.substring(colon2 + 1, semicolon2);
						String filename = recvMsg.substring(semicolon2 + 1);
						Query q = new Query(qid, filename);
						if(p2p.queryFlag && p2p.queryFile.equals(filename) && p2p.queryID == qid) {
							System.out.println("NT> Expected response, recording data...");
							p2p.ansAddr = InetAddress.getByName(addrStr);
							p2p.ansPort = Integer.valueOf(portStr);
							synchronized (p2p.ansFlag) {
								p2p.ansFlag = true;
							}
						}
						else synchronized (p2p.queries) {
							if (p2p.queries.contains(q) && !q.replied) {
								System.out.println("NT> Unexpected response, forwarding to the query senders...");
								int i = p2p.queries.indexOf(q);
								Query thisQuery = p2p.queries.get(i);
//								for(Neighbor n : thisQuery.senders) {
//									if(n == conn) continue;
//									try {
//										synchronized (n.outStream) {
//											n.outStream.writeBytes(recvMsg + "\n");
//										}
//										System.out.println("NT> Response forwarded to " + n.outStr + ".");
//									} catch (IOException e) {
//										e.printStackTrace();
//										System.out.println("NT> Failed forwarding response to " + n.outStr + ".");
//									}
//								}
								Neighbor n = thisQuery.sender;
								try {
									synchronized (n.outStream) {
										n.outStream.writeBytes(recvMsg + "\n");
									}
									System.out.println("NT> Response forwarded to " + n.outStr + ".");
								} catch (IOException e) {
									e.printStackTrace();
									System.out.println("NT> Failed forwarding response to " + n.outStr + ".");
								}
//								p2p.queries.remove(thisQuery);
								thisQuery.replied = true;
							}
							else {
								System.out.println("NT> Duplicate response, ignored.");
							}
						}
					}
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
				System.out.println("NTOT> Connection timeout with " + conn.outStr + ", closing connection.");
				synchronized (conn.stop) {
					conn.stop = true;
				}
			}

			if(sendTimer > p2p.HEARTBEAT_INTERVAL) {
//				System.out.println("NTT> Sending heartbeat message to " + conn.outStr);
				synchronized (conn.outStream) {
					try {
						conn.outStream.writeBytes("\n");
					} catch (IOException ioe) {
						// ioe.printStackTrace();
						System.out.println("NTOT> Failed to deliver the heartbeat message.");
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

class QueryTimeOutThread extends Thread {
	Query query;
	
	public QueryTimeOutThread (Query q) {
		query = q;
	}
	
	public void run() {
		try {
			sleep(p2p.RESPONSE_TIMEOUT);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		synchronized (p2p.queries) {
			if(p2p.queries.contains(query)) {
				System.out.println("QTOT> Query ID " + query.id + " time out. Removing.");
				p2p.queries.remove(query);
			}
		}
	}
}

// ------------------------------Main class------------------------------

public class p2p {
	// Constants
	static final int PDP_WAIT = 3000;
	static final int HEARTBEAT_INTERVAL = 10000;
	static final int HEARTBEAT_TIMEOUT = 12000;
	static final int RESPONSE_TIMEOUT = 5000;
	
	// Address and ports
	static InetAddress ipAddr;							// Local IP address
	static String      ipAddrStr;						// String format Local IP address
	static int         pdpPort;							// Port for peer discovery protocol (PDP)
	static int         ntcpPort;						// Port for neighboring TCP connection
	static int         ftcpPort;						// Port for file transfer TCP connection
	
	// Sockets
	static ServerSocket   ntcpServerSocket;				// Neighbor TCP welcome socket
	static DatagramSocket pdpSocket;    				// Peer Discovery Protocol UDP socket
	
	// Lists
	static Vector<Neighbor> neighbors;					// Neighboring peer connections (have TCP connections) 
	static Vector<Peer>     peers;						// All known peers
	static Vector<Query>    queries;					// Queries from another peer
	static Vector<String>   files;						// All sharing files
	
	// Variables
	static volatile int     queryID;
	static volatile String  queryFile;
	static volatile Boolean queryFlag;
	
	static volatile InetAddress ansAddr;
	static volatile int         ansPort;
	static volatile Boolean     ansFlag;
	
	static void readConfigPeer(){
		String pwd = p2p.class.getResource("").getPath() + "config_peer.txt";
		try {
			Scanner scn     = new Scanner(new File(pwd));
			String hostName = scn.next(); 
			
			ipAddr    = InetAddress.getByName(hostName);
			ipAddrStr = ipAddr.getHostAddress();
			ntcpPort  = scn.nextInt();
			ftcpPort  = scn.nextInt();
			pdpPort   = scn.nextInt();
			scn.close();
		} catch (FileNotFoundException fnfe) {
			System.out.println(fnfe);
			System.out.println("Warning: Lacking config_peer.txt, using default settings.");
			ntcpPort = 52020;
			ftcpPort = 52021;
			pdpPort  = 52022;
			try {
				ipAddr = InetAddress.getLocalHost();
				ipAddrStr = ipAddr.getHostAddress();
			} catch (UnknownHostException e) {};
		} catch (UnknownHostException e) {
			System.out.println(e);
		};
	}
	
	static void readConfigSharing() {
		String pwd = p2p.class.getResource("").getPath() + "config_sharing.txt";
		try {
			Scanner scn = new Scanner(new File(pwd));
			while(scn.hasNext()) {
				files.add(scn.nextLine());
			}
			scn.close();
		} catch (FileNotFoundException fnfe) {
			System.out.println(fnfe);
			System.out.println("Warning: Lacking config_sharing.txt!");
		}
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
	
	static void getFile(String file) {
		// Sending query
		System.out.println("Preparing the query...");
		Random r = new Random(System.currentTimeMillis());
		int qid = r.nextInt();
		System.out.println("Query ID: " + qid + ";");
		
		queryID = qid;
		queryFile = file;
		synchronized (queryFlag) {
			queryFlag = true;
		}
		synchronized (ansFlag) {
			ansFlag = false;
		}
		
		String queryMsg = "Q:" + queryID + ";" + file;
		System.out.println("Query message:<" + queryMsg + ">.");
		
		if(neighbors.size() == 0) {
			System.out.println("Error: No neighbor connected!");
			return;
		}
		
		for(Neighbor n : neighbors) {
			try {
				synchronized (n.outStream) {
					n.outStream.writeBytes(queryMsg + "\n");
				}
				System.out.println("Query sent to " + n.outStr + ".");
			} catch (IOException e) {
				e.printStackTrace();
				System.out.println("Failed sending query to " + n.outStr + ".");
			}
		}
		
		// Waiting for Response
		int count = 0;
		while (!ansFlag) {
			if(count >= RESPONSE_TIMEOUT) {
				System.out.println("Query time out, no such file found.");
				synchronized (queryFlag) {
					queryFlag = false;
				}
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			count += 100;
		}
		
		// Process response
		synchronized (queryFlag) {
			queryFlag = false;
		}
		System.out.println("Target file at " + ansAddr.getHostAddress() + ":" + ansPort + ".");
		
	}

	public static void main(String[] args) {
		
		neighbors = new Vector<Neighbor>();
		peers     = new Vector<Peer>();
		queries   = new Vector<Query>();
		files     = new Vector<String>();

		queryID   = 0;
		queryFile = new String("");
		queryFlag = false;
		ansFlag   = false;
		
		System.out.println("Starting the peer...");
		
//		if(args.length >= 3) {
//			ntcpPort = Integer.valueOf(args[0]);
//			ftcpPort = Integer.valueOf(args[1]);
//			pdpPort = Integer.valueOf(args[2]);
//			try {
//				ipAddr = InetAddress.getLocalHost();
//				ipAddrStr = ipAddr.getHostAddress();
//			} catch (UnknownHostException e) {
//				System.out.println(e);
//			}
//		}
//		else {
//			readConfigPeer();
//		}
		readConfigPeer();
		readConfigSharing();

		System.out.println("Neighbor TCP port: " + ntcpPort);
		System.out.println("File transfer TCP port: " + ftcpPort);
		System.out.println("Peer Discovery UDP port: " + pdpPort);
		System.out.println("Local HostAddress: "+ ipAddrStr);
		System.out.println("Local host name: "+ ipAddr.getHostName());
		
		System.out.println("Files ready to share:");
		for(String s : files) {
			System.out.println(s);
		}
		
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
			else if (cmd.equals("Get")) {
				String file = scn.next();
				System.out.println("Received command <Get " + file + ">.");
				getFile(file);
			}
			else if (cmd.equals("Leave")) {
				peers.clear();
				for(Neighbor i : neighbors) {
					i.close();
				}
				neighbors.clear();
				queries.clear();
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
}

