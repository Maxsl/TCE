package tce;

import tce.utils.CompressionTools;

import javax.net.ssl.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.nio.*;
//import java.nio.charset.Charset;

public class RpcConnection_Socket extends RpcConnection  {

	Socket 	_sock = null;

	ByteBuffer _buff;
	RpcAdapter _adapter = null;
	Thread 	_workthread = null;;

	public static final int META_PACKET_HDR_SIZE = 14;
	public static final int PACKET_META_MAGIC = 0xEFD2BB99;
	public static final int VERSION = 0x00000100;
	public static final  int MAX_PACKET_SIZE = 1024*1024*1;

	private long _sent_num = 0 ;
	protected Boolean _ssl = false;


	protected  RpcConnection_Socket(){

	}

	public RpcConnection_Socket(String host,int port){
		super(host,port);
		//_sock = new Socket();
	}

	public RpcConnection_Socket(String host,int port ,boolean ssl_enable){
		super(host,port);
		_ssl = ssl_enable;
	}

	//>=1 - ok(consumed   bytes) , 0 - need more, -1: data dirty
	// size - IN/OUT 剩余字节数
	private ReturnValue parseMsg(byte[] d,int size,Vector<byte[]> blocks){
		//ReturnValue rv = null;
		int p = 0;
		while(size>0){
			if(size < META_PACKET_HDR_SIZE){
				return new ReturnValue(0,size);
			}

			//Array not existed in android
			byte[] temp = new byte[META_PACKET_HDR_SIZE];
			System.arraycopy(d, p, temp, 0, temp.length);
			//ByteBuffer buf = ByteBuffer.wrap(Arrays.copyOfRange(d,p,p+META_PACKET_HDR_SIZE));
			ByteBuffer buf = ByteBuffer.wrap(temp);

			int magic = buf.getInt();
			int pkgsize = buf.getInt();
			byte compress = buf.get();
			byte encrypt = buf.get();
			int version = buf.getInt();
			if(magic != PACKET_META_MAGIC ){
				return new ReturnValue(-1,size);
			}
			if(pkgsize > MAX_PACKET_SIZE){
				return new ReturnValue(-1,size);
			}
			if(size <= META_PACKET_HDR_SIZE-4){
				return new ReturnValue(-1,size); // dirty
			}
			if( size < pkgsize+4 ){
				return new ReturnValue(0,size); // need more
			}

			//android traits
			temp = new byte[pkgsize-(META_PACKET_HDR_SIZE-4)];
			System.arraycopy(d, p+META_PACKET_HDR_SIZE, temp, 0, temp.length);

			if( RpcConsts.COMPRESS_ZLIB ==  compress){
				try{
					temp = CompressionTools.decompress(temp);
				}catch (Exception e){
					return new ReturnValue(-1,size);
				}
			}

			//byte[] b = Arrays.copyOfRange(d,p+META_PACKET_HDR_SIZE,p+pkgsize+4);
			byte[] b = temp;
			blocks.add(b);
			p += pkgsize+4;	//iterate p
			size -= pkgsize+4;

		}
		return new ReturnValue(1,size);
	}

	 public class ReturnValue{
		ReturnValue(int code_,int size_){
			code = code_;
			size = size_;
		}
		public int code=-1;
		public int size=0;
	}

	 static int count=0;;


	public class MessageReactor{

		byte[] bufs = new byte[1024*4];
		int p1 = 0;
		RpcConnection conn ;

		public MessageReactor(RpcConnection conn){
			this.conn = conn;
		}

		public boolean enqueue(byte[] d,int len){

			if( p1 + len > bufs.length){
				byte[] temp= new byte[p1+len];
				System.arraycopy(bufs,0, temp, 0, p1);
				bufs = temp;
			}
			System.arraycopy(d, 0,bufs,p1, len);
			p1 = p1+len;
			int r;
			Vector<byte[]> blocks = new Vector<byte[]>();
			ReturnValue rc = parseMsg(bufs,p1,blocks);
			r = rc.code;
			if( r == -1 ) { // data dirty
				RpcCommunicator.instance().getLogger().error("data dirty! closesocket()");
				return false;
			}

			if( p1 != rc.size){
				System.arraycopy(bufs, p1-rc.size, bufs, 0, rc.size);
				p1 = rc.size;
			}

			for(byte[] b: blocks ){
				RpcMessage m = RpcMessage.unmarshall(b);
				if(m == null){
					RpcCommunicator.instance().getLogger().error("decode message exception! closesocket()");
					return false;
				}else{
					m.conn = this.conn;
					try{
						this.conn.onMessage(m);
					}catch(Exception e){
						RpcCommunicator.instance().getLogger().error( e.toString());
					}
				}
			}
			return true;
		}
	}

	 @Override
	public void run(){
		long recved = 0 ;
		try{
			byte[] bufs = new byte[1024*4];
			byte[] d = new byte[1024];
			InputStream is = _sock.getInputStream();
			int len;
			int p1 = 0;

			MessageReactor reactor = new MessageReactor(this);
			onConnected();

			while (true){
				len = is.read(d);
				if(len < 0 ){
					_sock.close();
					break;
				}
				if( !reactor.enqueue(d,len)){
					close();
					break;
				}
			} // -- end while()

		}catch(Exception e){
			RpcCommunicator.instance().getLogger().error("connection lost! \n detail:" + e.toString());
		}
		onDisconnected();
		RpcCommunicator.instance().getLogger().debug("haha,connection thread exiting...");
	}

	@Override
	public boolean isConnected(){
		return _sock == null?true:false;
	}

	@Override
	public boolean connect(){
		InetSocketAddress ep = new InetSocketAddress(_host,_port);
		try{
			_sock = newSocket();
			_sock.connect(ep);
			_workthread = new Thread(this);
			_workthread.start();
			return true;
		}catch(Exception e){
			RpcCommunicator.instance().getLogger().error(e.getMessage());
		}
		_sock = null;
		return false;
	}

	protected Socket newSocket(){
		/*

			openssl s_client -debug -connect 192.168.14.101:16005
		 */
		Socket sock = null;
		if( !_ssl){
			sock = new Socket();
		}else{
			try{


				TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
					public java.security.cert.X509Certificate[] getAcceptedIssuers() {
						return null;
					}

//					public void checkClientTrusted(X509Certificate[] certs, String authType) {
//					}
					public void checkClientTrusted(java.security.cert.X509Certificate[] x509Certificates, java.lang.String s) throws java.security.cert.CertificateException{

					}
//					public void checkServerTrusted(X509Certificate[] certs, String authType) {
//					}

					public void checkServerTrusted(java.security.cert.X509Certificate[] x509Certificates, java.lang.String s) throws java.security.cert.CertificateException{

					}
				} };

//				SocketFactory sf = SSLSocketFactory.getDefault();
//				sock = (SSLSocket) sf.createSocket();

				SSLContext sslContext = SSLContext.getInstance("SSL");
//				String protocal = sslContext.getProtocol();
				sslContext.init(null, trustAllCerts,new java.security.SecureRandom());
//				SSLEngine e = sslContext.createSSLEngine();
//				System.out
//						.println("支持的协议: " + Arrays.asList(e.getSupportedProtocols()));
				sock = (SSLSocket) sslContext.getSocketFactory().createSocket();
			}catch (Exception e){
				System.out.println(e.toString());
			}
		}
		return sock;
	}

	@Override
	protected  void onDisconnected(){
		super.onDisconnected();
		_sock = null;
		_sent_num = 0 ; // 2013.11.25
	}


	@Override
	public void close(){
		try{
			if( _sock == null){
				return;
			}
			_sock.close();
			_sock = null;
//			if(_workthread !=null){
//				_workthread.interrupt();
//				_workthread.join();
//				_workthread = null;
//			}

		}catch(Exception e){
			RpcCommunicator.instance().getLogger().error(e.getMessage());
		}
	}

	//发送第一个包，检测token是否设置,如token设置，
	//则将__token__加入第一个包的extra字段
	@Override
	protected
	synchronized boolean  sendDetail(RpcMessage m){
		boolean rc = true;
		try{
			if( isConnected() == false){
				if( !connect() ){
					return false;
				}
			}
			////---------------------------------------
			// 连接建立之后第一个消息包携带 token和设备编号属性 到TGS，用于身份识别
			if(_sent_num == 0){
				if(_token!=null && !_token.equals("")){
					m.extra.setPropertyValue("__token__",_token);
					m.extra.setPropertyValue("__device_id__",RpcCommunicator.instance().getSystemDeviceID());
				}
			}
			_sent_num++;
			////---------------------------------------
//			OutputStream os;
//			os = _sock.getOutputStream();
			byte[]  d = m.marshall();

			ByteBuffer buf = ByteBuffer.allocate(META_PACKET_HDR_SIZE+d.length);
			buf.order(ByteOrder.BIG_ENDIAN);
			//pack hdr
			buf.putInt(PACKET_META_MAGIC);
			buf.putInt(d.length + META_PACKET_HDR_SIZE -4);
			if( d.length > 100){
				buf.put(  Integer.valueOf(RpcConsts.COMPRESS_ZLIB).byteValue());
				d = CompressionTools.compress(d);
			}else{
				buf.put(  Integer.valueOf(RpcConsts.COMPRESS_NONE).byteValue());
			}
			buf.put(  Integer.valueOf(RpcConsts.ENCRYPT_NONE).byteValue());
			buf.putInt(VERSION);
			buf.put(d);

			rc = sendBytes(buf.array());

//			byte[] d2 = buf.array();
//			os.write(buf.array());
//			d = d2;

		}catch(Exception e){
			e.printStackTrace();
			RpcCommunicator.instance().getLogger().error(e.toString());
			rc = false;
		}
		return rc;
	}

	@Override
	protected  boolean sendBytes(byte[] bytes){
		try {
			OutputStream os;
			os = _sock.getOutputStream();
			os.write( bytes );
		}catch (Exception e){
			RpcCommunicator.instance().getLogger().error(e.getMessage());
			return false;
		}
		return true;
	}

	@Override
	protected void join(){
		try{
			if( _workthread !=null){
				_workthread.join();
			}
		}catch(Exception e){
			RpcCommunicator.instance().getLogger().error(e.toString());
		}
	}
}
