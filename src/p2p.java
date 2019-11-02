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
	
	synchronized void close() {
		stop = true;
		try {
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
			System.out.println("PDP> Message received: \"" + recvMsg + "\".");
			String header = recvMsg.substring(0,3);
			
			try {
				int colon = recvMsg.lastIndexOf(":");
				addr = InetAddress.getByName(recvMsg.substring(3, colon));
				port = Integer.valueOf(recvMsg.substring(colon + 1));
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
					System.out.println("PDP> Ping from duplicate Peer, ignored.");
					continue;
				}
				
				// Load balancing
				if(p2p.neighbors.size() < 3) {
					sendPong(addr, port);
					msgBroadcast(recvData);
				}
				else {
					msgBroadcast(recvData);
					sendPong(addr, port);
				}
				
				p2p.peers.add(newPeer);
			}
			else if(header.equals("PO:")) {
				Peer fromPeer = new Peer(fromAddr, fromPort);
				if(p2p.peers.contains(fromPeer)) {
					System.out.println("PDP> Pong From duplicate peer, ignored.");
					continue;
				}
				
				Peer newPeer = new Peer(addr, fromPort);
				p2p.peers.add(newPeer);
				
				if(p2p.neighbors.size() < 2) {
					String peerStr = addr.getHostAddress() + ":" + port;
					System.out.println("PDP> Establishing TCP connection with " + peerStr + "...");
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
						System.out.println("PDP> Failed establishing TCP connection with " + peerStr + "!");
					}
					System.out.println("PDP> TCP connection established with " + peerStr + ".");
				}
			}
		}
	}
	
	void sendPong(InetAddress addr, int port) {// throws IOException{
		String thisPeerStr = p2p.ipAddr.getHostAddress() + ":" + p2p.ntcpPort;
		String peerStr = addr.getHostAddress() + ":" + port;
		
		String msg = new String("PO:" + thisPeerStr);
		System.out.println("PDP> Ready to send Pong message: \"" + msg + "\".");
		
		byte[] sendData = msg.getBytes();
		DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, addr, port);
		synchronized (p2p.pdpSocket) {
			try {
				p2p.pdpSocket.send(sendPacket);
			} catch (IOException e) {
				e.printStackTrace();
				System.out.println("PDP> Error: failed sending Pong message.");
			}
		}
		
		System.out.println("PDP> Pong message sent to " + peerStr + ".");
	}
	
	void msgBroadcast(byte[] sendData) {
		for (Peer p : p2p.peers) {
			System.out.println("PDP> Broadcasting " + new String(sendData).trim() + " to " + p.outStr + "...");
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
				System.out.println("PDP> TCP connection established with " + neighbor.outStr + ".");
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
					System.out.println("HB > Heartbeat message received from " + conn.outStr + ".");
				}
				else {
					int colon = recvMsg.indexOf(":");
					String header = recvMsg.substring(0, colon);
					int semicolon = recvMsg.indexOf(";");
					int qid = Integer.valueOf(recvMsg.substring(colon + 1, semicolon));
					
					if(header.equals("Q")) {
						System.out.println("QP > Received query message \"" + recvMsg + "\" from " + conn.outStr + ".");
						String filename = recvMsg.substring(semicolon + 1);
						Query q = new Query(qid, filename);
						if(p2p.queryFile.equals(filename) && p2p.queryID == qid) {
							System.out.println("QP > Loopback query, ignored.");
						}
						else if(p2p.files.contains(filename)) {
							System.out.println("QP > File found, sending response...");
							String reply = "R:" + qid + ";" + p2p.ipAddrStr + ":" + p2p.ftcpPort + ";" + filename + "\n";
							synchronized (conn.outStream) {
								conn.outStream.writeBytes(reply);
							}
							System.out.println("QP > Response sent to " + conn.outStr + ".");
						}
						else synchronized (p2p.queries) {
							if(p2p.queries.contains(q)) {
								System.out.println("QP > File not found, duplicate query, ignored.");
							}
							else {
								System.out.println("QP > File not found, forwarding to neighbors...");
								Query newQuery = new Query(qid, filename);
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
										System.out.println("QP > Query forwarded to " + n.outStr + ".");
									} catch (IOException e) {
										e.printStackTrace();
										System.out.println("QP > Failed forwarding query to " + n.outStr + ".");
									}
								}
							}
						}
					}
					else if(header.equals("R")) {
						System.out.println("QP > Received response message \"" + recvMsg + "\" from " + conn.outStr + ".");
						int colon2 = recvMsg.lastIndexOf(":");
						int semicolon2 = recvMsg.lastIndexOf(";");
						String addrStr = recvMsg.substring(semicolon + 1, colon2);
						String portStr = recvMsg.substring(colon2 + 1, semicolon2);
						String filename = recvMsg.substring(semicolon2 + 1);
						Query q = new Query(qid, filename);
						if(p2p.queryFlag && p2p.queryFile.equals(filename) && p2p.queryID == qid) {
							if(p2p.ansFlag) {
								System.out.println("QP > Duplicate expected response, ignored.");
							}
							else {
								System.out.println("QP > Expected response, recording data...");
								p2p.ansAddr = InetAddress.getByName(addrStr);
								p2p.ansPort = Integer.valueOf(portStr);
								synchronized (p2p.ansFlag) {
									p2p.ansFlag = true;
								}
							}
						}
						else synchronized (p2p.queries) {
							if (p2p.queries.contains(q) && !q.replied) {
								System.out.println("QP > Unexpected response, forwarding to the query senders...");
								int i = p2p.queries.indexOf(q);
								Query thisQuery = p2p.queries.get(i);
								Neighbor n = thisQuery.sender;
								try {
									synchronized (n.outStream) {
										n.outStream.writeBytes(recvMsg + "\n");
									}
									System.out.println("QP > Response forwarded to " + n.outStr + ".");
								} catch (IOException e) {
									e.printStackTrace();
									System.out.println("QP > Failed forwarding response to " + n.outStr + ".");
								}
								thisQuery.replied = true;
							}
							else {
								System.out.println("QP > Duplicate response, ignored.");
							}
						}
					}
				}
			} catch (IOException e) {
				// e.printStackTrace();
				System.out.println("QP > Connection lost with " + conn.outStr + ".");
				synchronized (conn.stop) {
					conn.stop = true;
				}
			}
		}
		conn.close();
		synchronized(p2p.neighbors) {
			if(p2p.neighbors.contains(conn))
				p2p.neighbors.remove(conn);
		}
	}
}

class NeighborTimeOutThread extends Thread {
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
				System.out.println("HB > Connection timeout with " + conn.outStr + ", closing connection.");
				synchronized (conn.stop) {
					conn.stop = true;
				}
			}

			if(sendTimer > p2p.HEARTBEAT_INTERVAL) {
				System.out.println("HB > Sending heartbeat message to " + conn.outStr);
				synchronized (conn.outStream) {
					try {
						conn.outStream.writeBytes("\n");
					} catch (IOException ioe) {
						// ioe.printStackTrace();
						System.out.println("HB > Failed to deliver the heartbeat message.");
						synchronized (conn.stop) {
							conn.stop = true;
						}
					}
				}
				sendTimer = 0;
			}
			
			try {
				sleep(p2p.TIMER_INTERVAL);
			} catch (InterruptedException e) {};
			
			synchronized (conn.timer) {
				conn.timer += p2p.TIMER_INTERVAL;
			}
			sendTimer += p2p.TIMER_INTERVAL;
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
				System.out.println("QP > Query ID " + query.id + " time out. Removing.");
				p2p.queries.remove(query);
			}
		}
	}
}

class FileTransferThread extends Thread {
	@Override
	public void run() {
		while (true) {
			try {
				Socket socket = p2p.ftcpServerSocket.accept();
				BufferedReader inStream = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				DataOutputStream outStream = new DataOutputStream(socket.getOutputStream());
				String hostName = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
				System.out.println("TP > TCP Connection established with " + hostName + ".");
				
				
				String recvMsg = inStream.readLine();
				int colon = recvMsg.indexOf(":");
				if(colon < 0) continue;
				String header = recvMsg.substring(0, colon);
				if(header.equals("T")) {
					String fileName = recvMsg.substring(colon + 1);
//					System.out.println("TP > File transfer request for " + fileName + "." );
					System.out.println("TP > Received file transfer request for " + fileName + "." );
					String path = p2p.class.getResource("").getPath() + "/shared/" + fileName;
					try {
						System.out.println("TP > File transfer started..." );
						File inFile = new File(path);
						FileInputStream fInStream = new FileInputStream(inFile);
						byte[] buffer = new byte[p2p.BUFFER_SIZE];
						int len = 0;
						while (true) {
							len = fInStream.read(buffer);
							if(len != -1) {
								outStream.write(buffer,0,len);
							}
							else break;
						}
						System.out.println("TP > File transfer successfully finished.");
						fInStream.close();
						socket.close();
					} catch (FileNotFoundException e) {
						System.out.println("TP > Critical Error: File not found!");
						outStream.writeBytes("\n");
					}
				}
				inStream.close();
				outStream.close();
				socket.close();
			} catch (IOException e) {
				e.printStackTrace();
				System.out.println("TP > IOException occured during file transfer.");
			}
		}
	}
}

// ------------------------------Main class------------------------------

public class p2p {
	// Constants
	static final int HEARTBEAT_INTERVAL = 10000;		// Interval for sending heart beat messages
	static final int HEARTBEAT_TIMEOUT  = 12000;		// Timeout for not receiving heart beat messages
	static final int RESPONSE_TIMEOUT   = 5000;			// Timeout for not receiving response for a query
	static final int TIMER_INTERVAL     = 100;			// Interval of timers
	static final int QUERY_ID_RANGE     = 2147483647;	// Range of random Query ID
	static final int BUFFER_SIZE        = 1024;			// Size of transporting buffer 
	
	// Address and ports
	static InetAddress ipAddr;							// Local IP address
	static String      ipAddrStr;						// String format Local IP address
	static int         pdpPort;							// Port for peer discovery protocol (PDP)
	static int         ntcpPort;						// Port for neighboring TCP connection
	static int         ftcpPort;						// Port for file transfer TCP connection
	
	// Sockets
	static ServerSocket   ftcpServerSocket;             // File transfer TCP welcome socket
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
			System.out.println("Error: Lacking config_sharing.txt!");
		}
	}
	
	static void pdpInit(){
		try {
			pdpSocket = new DatagramSocket(pdpPort);
			PdpThread PdpThread = new PdpThread(); 
			PdpThread.start();
			System.out.println("Info: Peer discovery service started.");
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("Error: Failed starting Peer discovery service.");
		}
	}
	
	static void ntcpInit() {
		try {
			ntcpServerSocket = new ServerSocket(ntcpPort);
			NeighborWelcomeThread neighborWelcomeThread = new NeighborWelcomeThread(); 
			neighborWelcomeThread.start();
			System.out.println("Info: Neighbor service started.");
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("Error: Failed starting neighbor welcome service.");
		}
	}
	
	static void ftcpInit() {
		try {
			ftcpServerSocket = new ServerSocket(ftcpPort);
			FileTransferThread fileTransferThread = new FileTransferThread(); 
			fileTransferThread.start();
			System.out.println("Info: File transfer service started.");
		} catch (Exception e) {
			System.out.println(e);
			System.out.println("Error: Failed starting file transfer service.");
		}
	}
	
	static void pdpSendMsg(InetAddress addr, int port) {
		String msg = new String("PI:" + ipAddrStr + ":" + pdpPort);
		System.out.println("Sending Ping message: \"" + msg + "\" to " + addr.getHostAddress() + ":" + port + "...");
		byte[] sendData = msg.getBytes();
		DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, addr, port);
		try {
			pdpSocket.send(sendPacket);
			System.out.println("Ping message successfully sent.");
		} catch (IOException e) {
			System.out.println(e);
		}
    }
	
	static void getFile(String fileName) {
		// Sending query
		Random r = new Random(System.currentTimeMillis());
		int qid = r.nextInt(QUERY_ID_RANGE);

		queryID = qid;
		queryFile = fileName;
		synchronized (queryFlag) {
			queryFlag = true;
		}
		synchronized (ansFlag) {
			ansFlag = false;
		}
		
		String queryMsg = "Q:" + queryID + ";" + fileName;
		System.out.println("Query ID: " + qid + ", message: \"" + queryMsg + "\".");
		
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
				Thread.sleep(TIMER_INTERVAL);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			count += TIMER_INTERVAL;
		}
		
		// Process response
		synchronized (queryFlag) {
			queryFlag = false;
		}
		System.out.println("Target file at " + ansAddr.getHostAddress() + ":" + ansPort + ".");
		
		// Getting the file
		try {
			Socket socket = new Socket(ansAddr, ansPort);
			InputStream inStream = socket.getInputStream();
			DataOutputStream outStream = new DataOutputStream(socket.getOutputStream());
			System.out.println("Connected to " + ansAddr.getHostAddress() + ":" + ansPort + ".");
			
			outStream.writeBytes("T:" + fileName + "\n");
			System.out.println("File request sent to server, waiting for transfer.");
			
			String path = p2p.class.getResource("").getPath() + "/obtained/" + fileName;
			File outFile = new File(path);
			if(!outFile.exists()) {
				try {
					outFile.createNewFile();
					System.out.println("File /obtained/" + fileName + " created.");
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			
			System.out.println("Start receiving file...");
			FileOutputStream fOutStream = new FileOutputStream(outFile);
			byte[] buffer = new byte[BUFFER_SIZE];
			int len = 0;
			while (true) {
				len = inStream.read(buffer);
				if(len != -1) {
					fOutStream.write(buffer, 0, len);
				}
				else break;
			}
			
			System.out.println("File received and saved.");
			fOutStream.close();
			socket.close();
		} catch (IOException ioe) {
			ioe.printStackTrace();
			System.out.println("Error: Failed connecting to file server.");
		}
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
		ftcpInit();
		
		Scanner scn = new Scanner(System.in);
		
		while (true) {
			String cmd = scn.next();
			if(cmd.equals("Connect")) {
				String addrStr = scn.next();
				int port = scn.nextInt();
				
				System.out.println("Received command \"Connect " + addrStr + " " + port + "\".");
				
				try {
					InetAddress addr = InetAddress.getByName(addrStr);
					pdpSendMsg(addr, port);
				} catch (UnknownHostException e) {
					System.out.println(e);
				}
			}
			else if (cmd.equals("Get")) {
				String file = scn.next();
				System.out.println("Received command \"Get " + file + "\".");
				getFile(file);
			}
			else if (cmd.equals("Leave")) {
				System.out.println("Received command \"Leave\".");
				System.out.println("Closing connections...");
				peers.clear();
				synchronized (neighbors) {
					for(Neighbor i : neighbors) {
						i.close();
					}
					neighbors.clear();
				}
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

