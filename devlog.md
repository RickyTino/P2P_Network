## 20191101
- 服务器上测过通过了
- 不太重要的问题：对于重复的response，重复record
- 修复上述问题
- 全部功能完成，本地测试通过
- 明天上服务器测试
- 试一试:peers能不能弄个timeout，以允许Leave之后重进？
- 
## 20191031
- 修复了部分线程安全问题
- 基本完成查询协议
  - 问题：一个peer在收到response之后又收到request，是否判断重复？
  - 解决方案：Query的移除只在timeout进行，是否收到回复要另外标记
  - 已解决
- 心跳包输出被注释掉了，记得恢复
- 接下来就是文件传输了！
## 20191029
- 关于Query protocol:
  - 新建类：Query，包含queryID、filename和ArrayList\<Neighbor\> senders;
  - 新建静态变量：ArrayList\<Query\> queries;
  - p2p类的静态变量：
    - queryFlag, queryID, queryFile;
    - ansFlag， ansAddr, ansPort
  - 主线程函数：收到Get file命令时：
    - if(自己有该文件) 打印提示并不做操作;
    - else 
      - 更新queryID和queryFile，syn设queryFlag=true;
      - 调用socket.outStream发出query;
      - 等待ansFlag并检测超时，超时打印信息并退出函数；
      - 读取ansAddr和ansPort; （测试阶段：输出地址和port就行）
      - 新建Socket(ansAddr, ansPort) 并发出文件请求；
      - 接收并保存文件
  - NT线程：
    - if (接收到query)
      - if (自己有该文件) 组织回送信息;
      - else if (queries中有记录重复query) 向该query的senders中添加该线程的conn;
      - else 新建query并添加到queries，然后forward message;
    - else if (接收到answer)
      - if (query三件套符合) 写ansAddr和ansPort，syn设置ansFlag=true;
      - else if (queries中有符合记录) 向每个sender转发消息，并删除query
      - else 丢弃该消息
  - TWT线程：
    - 建立ServerSocket监听
    - while (true)
      - Socket s = serverSocket.accept();
      - 读取请求，处理字符串，获取文件名
      - 读文件并向socket传输
      - s.close();

## 20191025
- 第一阶段目标：UDP Peer Discovery Protocol
- main: 6个不同端口号的主线程
  - 主线程：connect函数
    - connect(ip, port): ping并collect pong
    - 线程：接收、处理和forward ping message

## 20191020
- 要学的：
  - java socket
  - java 多线程
  - java 文件读写
- 解决查询broadcast storm：
  - 记录状态的方式
  - TTL，已发邮件询问
  - ID？
- 组网UDP能否

----------------------------------------------------------
## 开发要求
### 目录结构:
- 工作目录：~/p2p/
- config_peer.txt - 本地端口号
- config_sharing.txt - 共享的文件名  
- shared/ - 共享文件目录，保持与config_sharing.txt一致
- obtained/ - 下载目录

### 端口
- 两个TCP port，一个用于邻居连接、查询，一个用于临时传输文件
- 一个UDP port，用于发现邻居
- 端口号:52020-52039

### 指令列表：
- Get [file] 
  - 运行文件查询协议（QP），寻找并下载文件
- Leave 
  - 关闭与邻居的所有连接
- Connect [IP] [port] 
  - 连接指定主机
- Exit 
  - 关闭连接并结束进程

### 启动时：
- 在config_peer文件所示的3个端口号上开启2个TCP连接和1个UDP连接
- 等待stdin
- 用户键入Connect命令组网，程序运行一个对等方发现协议（PDP），在收集响应一段时间后，选择两个响应的对等方建立邻居TCP连接。
- 在以上步骤后，程序等待用户输入，并：
  - 等待邻居的文件查询/获取请求；
  - 定期向邻居发送心跳包
  - 监听welcome socket，等待连接请求

### 要求：
- 6个对等方，6台不同的主机，连接方式自定；
- 每个对等方必须同时：
  - 等待来自命令行的文件请求
  - 定期检查邻居的状态（发送心跳包）并相应邻居的心跳包
  - 监听并响应对等方的文件查询请求
  - 监听并响应对等方的文件获取请求
- 必须在以下时刻打印输出信息：
  - 启动
  - 连接对等方（连接前&连接后成功/失败）
  - 接受对等方的连接请求
  - 向对等方发送一个请求
  - 收到对等方的查询（收到&查询结果）
  - 向邻居发送心跳包
  - 收到邻居的心跳包
  - 心跳包超时关闭连接
  - 向对等方请求文件传输
  - 收到对等方的文件传输请求
  - 完成文件传输

### 协议
- Query Protocol (QP)
  - 请求格式：Q:[query ID];[filename]
  - 响应格式：R:[query ID];[peer IP:port];[filename]
  - 响应发给邻居，而不是发起者。回溯请求时的路径。
  - 重复的响应包如何处理？
- Transfer Protocol (TP)
  - 请求格式：T:[filename]
  - 响应就是文件的文本，紧接着关闭连接
  - 该协议运行在单独的点对点TCP连接上
- Peer Discovery Protocol (PDP)
  - ping: PI:[joining IP]:[port]
  - pong: PO:[responding IP]:[neighbor TCP port]
  - 每个第一次收到该消息的peer直接响应发送方，并安静地丢掉重复包。


### 待定的参数：
- 向邻居发送心跳包的时延
- 心跳包超时时延
- 收集PDP pong的时长
