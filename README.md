# P2P Network
Author: Ricky Chai
E-mail: kxc675@case.edu

## Project
EECS 425 project 1  
See the PDF file attached.

## Some Specification
- Minor modification on config_peer.txt:
  - The first line should be the name or address of the host;
  - Three lines follows specified the ports to be used.
- About the prefix of every output lines:
  - "PDP>" indicates that this is a message related with peer discovery protocol;
  - "QP >" indicates that this is a message related with query protocol;
  - "TP >" indicates that this is a message related with transfer protocol;
  - "HB >" indicates that this is a message related with heartbeat protocol;
  - If there's no prefix, it is the output of the main thread.
- A peer can connect back immediately after leaving the network;
  - Other peers keep the peer discovery protocol history for a limited time;
  - A sign "fresh" is used for new joining peer to avoid broadcast storm
  - Only with peers that have fresh == true, duplicate ping message is ignored.
  - After a certain timeout, fresh is set to false.
- It is possible to use host names instead of IP addresses in the given "Connect" command;
  -  e.g. "Connect eecslab-10.case.edu 52020".
- All the message needed for connection is printed after startup;
  - Includes the port numbers, IP addresses, host name and files to be shared.
- Due to different Java versions (Mine is 10.0.1), a re-compile is needed before running on the server.