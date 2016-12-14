package server;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import dataBase.*;
import msg.*;
import tools.*;

public class ServerThread extends Thread {
	private Socket client;
	private OutputStream ous;
	private int UserJK;
	private boolean is_Online = false;
	private UserModel model;

	public int getUserJK() {
		return UserJK;
	}

	public ServerThread(Socket client) {
		this.client = client;
	}

	public void run() {
		while (!is_Online) { // 该线程中客户端未登陆
			try {
				processLogin();
			} catch (Exception e) {

				/*
				 * 客户端断开连接
				 */
				System.out.println(client.getRemoteSocketAddress() + "已断开");
				try {
					client.close();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				break;
			}
		}
		while (is_Online) { // 该线程中客户端已登陆
			try {
				processChat();
			} catch (Exception e) {
				/*
				 * 客户端断开连接
				 */
				System.out.println(client.getRemoteSocketAddress() + "已断开");
				ThreadRegDelTool.DelThread(this);// 从线程数据库中间删除这条信息
				try {
					client.close();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				break;
			}
		}
	}

	/*
	 * 该方法用于处理从客户端传过来的信息 (未登录)
	 */
	public void processLogin() throws Exception {
		//connect to DataBase
		model = new UserModel(DBConnection.getInstance());
		
		ous = client.getOutputStream();
		InputStream ins = client.getInputStream();
		DataInputStream dis = new DataInputStream(ins);
		int totalLen = dis.readInt();
		byte[] data = new byte[totalLen - 4];
		dis.readFully(data);
		MsgHead msg = ParseTool.parseMsg(data);// 解包该信息

		/*
		 * 下面是针对不同的信息进行处理
		 */

		// 如果传过来的是注册信息
		if (msg.getType() == 0x01) {
			MsgReg mr = (MsgReg) msg;

			// 注册用户

			UserInfo newuser = model.createUser(mr.getPwd(), mr.getNikeName(), 1);
			int JKNum = newuser.getJKNum();

			/*
			 * 服务器准备返回信息
			 */

			MsgRegResp mrr = new MsgRegResp();
			int Len = 14;// MsgRegResp的长度为14
			byte msgtype = 0x11;// MsgRegResp的类型为0x11
			byte state = 0;

			// 设置MsgRegResp的各个参数
			mrr.setTotalLen(Len);
			mrr.setType(msgtype);
			mrr.setDest(JKNum); // MsgRegResp的Dest是注册好的JK号
			mrr.setSrc(Figures.ServerJK); // 服务器的JK号
			mrr.setState(state);

			// 写入流中
			byte[] sendmsg = PackageTool.packMsg(mrr);// 将传输的信息打包
			ous.write(sendmsg);
			ous.flush();

		}

		// 如果传过来是登陆信息
		else if (msg.getType() == 0x02) {
			MsgLogin ml = (MsgLogin) msg;
			byte checkmsg;// 用来保存状态信息

			// DAO验证用户是否存在
			System.out.println(model.userAuthorization(ml.getSrc(), ml.getPwd()));
			if (model.userAuthorization(ml.getSrc(), ml.getPwd())) {// 如果验证了用户存在
				checkmsg = 0;
			} else {
				checkmsg = 1;
			}

			/*
			 * 服务器准备返回信息
			 */
			MsgLoginResp mlr = new MsgLoginResp();
			int len = 14;
			byte msgtype = 0x22;

			// 设置resp的各个参数
			mlr.setTotalLen(len);
			mlr.setType(msgtype);
			mlr.setDest(Figures.LoginJK);
			mlr.setSrc(Figures.ServerJK);
			mlr.setState(checkmsg);

			// 写入流中
			byte[] sendmsg = PackageTool.packMsg(mlr);// 将传输的信息打包
			ous.write(sendmsg);
			ous.flush();

			
			
			/*
			 * 如果登陆操作完成， 发送好友列表
			 */
			if (checkmsg == 0) {
				UserJK = ml.getSrc();
				ThreadRegDelTool.RegThread(this); // 向线程数据库中注册这个线程
				UserInfo user = model.getUserByJK(ml.getSrc());
				msgtype = 0x03;
				String userName = user.getNickName();
				int pic = user.getAvatar();
				byte listCount = user.getCollectionCount();
				byte[] bodyCount = user.getBodyCount();
				byte[][] bodyState;
				int[][] BodyNum = user.getBodyNum();
				int[][] BodyPic = user.getBodypic();
				/*
				 * 计算长度
				 */
				int i, j;

				len = 13; // 信息头长度
				len += 10; // userName
				len += 4;  //pic
				len += 1; // listCount
				len += (10 * listCount); // listName
				len += listCount; // bodyCount

				bodyState = new byte[listCount][];

				for (i = 0; i < listCount; i++) {
					len += bodyCount[i] * 19; // 每个好友长度为19

					bodyState[i] = new byte[bodyCount[i]];
				}

				/*
				 * 检查好友在线状态 实现方法 去线程数据库看看是不是存在同样JKNUM的线程
				 */

				for (i = 0; i < listCount; i++) {
					for (j = 0; j < bodyCount[i]; j++) {
						if (ThreadDB.threadDB.containsKey(String.valueOf(BodyNum[i][j]))) {
							bodyState[i][j] = 0;
						} else {
							bodyState[i][j] = 1;
						}
					}
				}

				// 设置mtl的各个参数
				MsgTeamList mtl = new MsgTeamList();
				mtl.setTotalLen(len);
				mtl.setType(msgtype);
				mtl.setDest(ml.getSrc());
				mtl.setSrc(Figures.ServerJK);
				mtl.setUserName(userName);
				mtl.setPic(pic);
				mtl.setListCount(listCount);
				mtl.setListName(user.getListName());
				mtl.setBodyCount(bodyCount);
				mtl.setBodyNum(BodyNum);
				mtl.setBodyPic(BodyPic);
				mtl.setNikeName(user.getBodyName());
				mtl.setBodyState(bodyState);


				// 写入流中
				sendmsg = PackageTool.packMsg(mtl);
				
				ous.write(sendmsg);
				ous.flush();
				is_Online = true;// 设置已登录客户端
			}

		}

	}

	/*
	 * 该方法用于处理从客户端传过来的信息 (已登录)
	 */
	public void processChat() throws Exception {
		InputStream ins = client.getInputStream();
		DataInputStream dis = new DataInputStream(ins);
		
		int totalLen = dis.readInt();
		byte[] data = new byte[totalLen - 4];
		dis.readFully(data);
		MsgHead msg = ParseTool.parseMsg(data);// 解包该信息

		/*
		 * 下面是针对不同的信息进行处理
		 */

		if (msg.getType() == 0x04) {//如果收到的是发送信息请求
			MsgChatText mct = (MsgChatText) msg;
			int from = mct.getSrc();
			int to = mct.getDest();
			String msgText = mct.getMsgText();
			System.out.println("Sending Test!!");
			System.out.println("From "+from+" To "+to+" Text "+msgText);
			
			if(!ChatTool.sendMsg(from, to, msgText)){
				System.out.println("SaveOnServer");
				
				//保存到服务器上	
				ChatTool.saveOnServer(from, to,msgText);
			}
		}

	}
	
	/*
	 * 该方法用来向用户发送其他人来的信息
	 */
	public void SendMsg(int from,String msg) throws IOException{
		MsgChatText mct = new MsgChatText();
		int totalLen = 13;
		byte msgType = 0x04;
		byte[] data = msg.getBytes();
		totalLen += data.length;
		
		mct.setTotalLen(totalLen);
		mct.setType(msgType);
		mct.setDest(UserJK);
		mct.setSrc(from);
		mct.setMsgText(msg);
		
		byte[] send = PackageTool.packMsg(mct);
		ous.write(send);
		ous.flush();

	}
	
	

}
