package com.heshun.blecustom.activity;

import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AnticipateOvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.heshun.blecustom.R;
import com.heshun.blecustom.adapter.CMDListAdapter;
import com.heshun.blecustom.base.BaseResponseBody;
import com.heshun.blecustom.base.BleMessage;
import com.heshun.blecustom.base.Head;
import com.heshun.blecustom.entity.CMDItem;
import com.heshun.blecustom.entity.ChargeMode;
import com.heshun.blecustom.entity.requestBodyEntity.ChargeNowRequest;
import com.heshun.blecustom.entity.requestBodyEntity.DownloadPackageRequest;
import com.heshun.blecustom.entity.requestBodyEntity.GunIdRequest;
import com.heshun.blecustom.entity.requestBodyEntity.RemoteUpgradeRequest;
import com.heshun.blecustom.entity.requestBodyEntity.SetChargeModeRequest;
import com.heshun.blecustom.entity.requestBodyEntity.SetVolumeRequest;
import com.heshun.blecustom.entity.requestBodyEntity.SysInfoRequest;
import com.heshun.blecustom.entity.requestBodyEntity.TimeSyncRequest;
import com.heshun.blecustom.entity.responseBodyEntity.DownloadPackageResponse;
import com.heshun.blecustom.entity.responseBodyEntity.DownloadSuccessfullyResponse;
import com.heshun.blecustom.tools.FileUtils;
import com.heshun.blecustom.tools.ToolsUtils;
import com.heshun.blecustom.wheel.DividerItemDecoration;
import com.heshun.blecustom.wheel.StrericWheelAdapter;
import com.heshun.blecustom.wheel.WheelView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import static com.heshun.blecustom.tools.ToolsUtils.writeMsg;

/**
 * author：Jics
 * 2017/6/5 16:20
 */
public class CMDListActivity extends Activity implements CMDListAdapter.CmdItemClickListener, View.OnClickListener {
	private String TAG = "cmd_request";
	public static final int TIME_OUT = 1;//超时时间
	private RecyclerView recyclerView;
	private AlertDialog.Builder loadingDialog;
	private AlertDialog dialog;
	private Timer timer;
	private int timeOut = TIME_OUT;
	private byte currentCMD = (byte) 0xFF;
	private Button btn_dis_connect;
	private Button btn_connect;
	private boolean fileSucc = false;//文件是否加载成功
	private boolean isDownloadding = false;//是否有下载任务
	private byte[] fileblocks;//文件的byte流
	// ----------- 蓝牙部分 -----------
	private boolean isTiming = false;//是否正在计时
	byte[] responseBuffer = new byte[0];//接收数据buff


	public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
	public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
	private String mDeviceName;
	private String mDeviceAddress;
	private BluetoothLeService mBluetoothLeService;
	private boolean mConnected = false;
	private BluetoothGattCharacteristic mWriteCharacteristic;//全局可读写的特征()

	// 蓝牙服务的ServiceConnection
	private final ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName componentName, IBinder service) {
			//通过binder获取到BluetoothLeService的实体
			mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
			if (!mBluetoothLeService.initialize()) {
				Log.e(TAG, "初始化蓝牙连接失败");
				finish();
			}
			mBluetoothLeService.connect(mDeviceAddress);
		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			mBluetoothLeService = null;
		}
	};
	private String filePath;//文件路径
	// ------------ END -------------


	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_cmd_list);
		initView();
	}

	/**
	 * 初始化视图
	 */
	private void initView() {
		//获取bundle里的数据
		final Intent intent = getIntent();
		mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
		mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);
		//绑定并启动蓝牙服务
		Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
		bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

		btn_dis_connect = (Button) findViewById(R.id.btn_dis_connect);
		btn_dis_connect.setText(btn_dis_connect.getText().toString() + "( " + mDeviceName + " )");
		btn_connect = (Button) findViewById(R.id.btn_connect);
		btn_connect.setText(btn_connect.getText().toString() + "( " + mDeviceName + " )");
		btn_connect.setOnClickListener(this);
		btn_dis_connect.setOnClickListener(this);

		recyclerView = (RecyclerView) findViewById(R.id.list_contener);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL_LIST));
		CMDListAdapter cmdListAdapter = new CMDListAdapter(this, makeCMDList());
		cmdListAdapter.setCmdItemClickListener(this);
		recyclerView.setAdapter(cmdListAdapter);
		loadingDialog = new AlertDialog.Builder(this);
	}

	/**
	 * 生成列表
	 * 可重构出去
	 *
	 * @return
	 */
	private List<CMDItem> makeCMDList() {
		List<CMDItem> cmdList = new ArrayList<>();
		cmdList.add(new CMDItem(false, "时间同步", Head.CMD_TIME_SYNCHRONIZATION));
		cmdList.add(new CMDItem(false, "查询系统信息", Head.CMD_QUERY_SYSTEM_INFORMATION));
		cmdList.add(new CMDItem(true, "设置音量", Head.CMD_SET_VOLUME));
		cmdList.add(new CMDItem(true, "设置充电模式", Head.CMD_SET_CHARGE_MODE));
		cmdList.add(new CMDItem(true, "立即充电", Head.CMD_CHARGE_NOW));
		cmdList.add(new CMDItem(true, "查询状态", Head.CMD_QUERY_STATE));
		cmdList.add(new CMDItem(true, "结束充电", Head.CMD_END_CHARGE));
		cmdList.add(new CMDItem(true, "查询充电历史记录", Head.CMD_QUERY_CHARGING_HISTORY));
		cmdList.add(new CMDItem(true, "查询累计电量", Head.CMD_QUERY_CUMULATIVE_CHARGE));
		cmdList.add(new CMDItem(true, "启动远程升级", Head.CMD_START_REMOTE_UPGRADE));
		return cmdList;
	}

	@Override
	public void onCmdClick(boolean isJump, byte cmd) {
		if (isDownloadding) {//远程升级过程中不允许其他操作
			Toast.makeText(CMDListActivity.this, "远程升级过程中不允许其他操作", Toast.LENGTH_SHORT).show();
		} else {
			if (mConnected) {
				if (mWriteCharacteristic != null) {
					//发送（head+body）命令
					makeCMD(cmd);
				} else {
					Toast.makeText(CMDListActivity.this, "未配对此设备或此设备无可匹配UUID的特征值", Toast.LENGTH_SHORT).show();
				}

			} else {
				Toast.makeText(this, "蓝牙未连接", Toast.LENGTH_SHORT).show();
			}
		}
	}

	/**
	 * 信息出口
	 * 普通指令信息
	 * 响应数据处理，接收普通指令数据
	 * 数据包大小限制20byte,分包接收两条数据间隔1秒视为数据接收完毕
	 * 超时或者接收数据完整后将请求命令设为失效 (byte)0xFF
	 *
	 * @param msgBytes
	 */
	private void sendMsg(byte cmd, final byte[] msgBytes) {
		if (cmd == Head.CMD_START_REMOTE_UPGRADE || cmd == Head.CMD_REQUEST_DOWNLOAD_PACKAGE || cmd == Head.CMD_PACKAGE_DOWNLOAD_SUCCESSFULLY) {
			currentCMD = cmd;//赋值当前命令
			Log.e(TAG, "请求的数据: " + Arrays.toString(msgBytes));
			writeMsg(msgBytes, mWriteCharacteristic, mBluetoothLeService);
		} else {
			currentCMD = cmd;//赋值当前命令
			Log.e(TAG, "请求的数据: " + Arrays.toString(msgBytes));
			loadingDialog.setTitle("数据接收中");
			loadingDialog.setCancelable(false);
			loadingDialog.setView(LayoutInflater.from(this).inflate(R.layout.dialog_loading, null));

			dialog = loadingDialog.create();
			dialog.show();
			timer = new Timer();//初始化计时器
			isTiming = true;//开始计时，等待广播发送相应数据
			// 拆分成 20B 的包发送
			writeMsg(msgBytes, mWriteCharacteristic, mBluetoothLeService);
			//发送成功后开始计时
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					if (timeOut-- <= 0) {//计时结束

						timer.cancel();//取消计时器
						isTiming = false;//重置计时标识
						timeOut = TIME_OUT;//重置计时数据
//					responseBuffer= DataSimulation.getHistoryData();//模拟数据
						if (responseBuffer.length != 0) {//有数据的情况
							BaseResponseBody responseBody = new BleMessage().decodeMessage(responseBuffer);
							if (responseBody != null) {
								String result = responseBody.toString();
								Intent intent = new Intent(CMDListActivity.this, CMDJumpHereActivity.class);
								intent.putExtra("CMDCode", currentCMD + "");
								intent.putExtra("result", result);
								startActivity(intent);
							} else {//数据校验出错的情况
								runOnUiThread(new Runnable() {
									@Override
									public void run() {
										Toast.makeText(CMDListActivity.this, "响应体不完整或数据校验失败", Toast.LENGTH_SHORT).show();
									}
								});
							}
						} else {//无数据的情况
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									Toast.makeText(CMDListActivity.this, "响应超时", Toast.LENGTH_SHORT).show();
								}
							});
						}

						if (dialog.isShowing()) {
							dialog.dismiss();
						}
						currentCMD = (byte) 0xFF;//还原当前命令
						responseBuffer = new byte[0];
					}
				}//end run
			}, 0, 1000);
		}


	}


	/**
	 * 接收下载指令
	 * 解码加编码
	 *
	 * @param recive
	 */
	private void receiveDownloadCMD(byte[] recive) {
		if (recive.length >= 8) {//长度够的话先获取到CMD指令
			currentCMD = recive[1];//获取响应包里的指令类型
			System.out.println("接收到的请求包信息CMD为" + currentCMD);
			if (currentCMD == Head.CMD_REQUEST_DOWNLOAD_PACKAGE) {//解析完响应体就发送包
				BleMessage bleMessage = new BleMessage();
				System.out.println("收到的下载数据请求包" + Arrays.toString(recive));
				DownloadPackageResponse downloadPackageResponse = (DownloadPackageResponse) bleMessage.decodeMessage(recive);
				if (downloadPackageResponse != null && fileblocks != null) {
					int startIndex = downloadPackageResponse.getStatrIndex();
					int endIndex = downloadPackageResponse.getEndIndex();
					//进度显示
					btn_connect.setText("已连接上( " + mDeviceName + " ) " + ToolsUtils.getPercentage(fileblocks, endIndex) + "");
					Head downHead = new Head(Head.CMD_TYPE_RESPONSE, Head.CMD_REQUEST_DOWNLOAD_PACKAGE);
					DownloadPackageRequest downloadPackageRequest = new DownloadPackageRequest(ToolsUtils.getFileBlock(fileblocks, startIndex, endIndex));
					byte[] bytes = bleMessage.encodeMessage(downHead, downloadPackageRequest);
					System.out.println(Arrays.toString(bytes));
					// 拆分成 20B 的包发送
					writeMsg(bytes, mWriteCharacteristic, mBluetoothLeService);
				}
			} else if (currentCMD == Head.CMD_PACKAGE_DOWNLOAD_SUCCESSFULLY) {
				isDownloadding = false;
				System.out.println("收到的下载完成请求包" + Arrays.toString(recive));
				BleMessage bleMessage = new BleMessage();
				DownloadSuccessfullyResponse downloadSuccessfullyResponse = (DownloadSuccessfullyResponse) bleMessage.decodeMessage(recive);
				if (downloadSuccessfullyResponse != null) {
					isDownloadding = false;
					String result = downloadSuccessfullyResponse.toString();
					Intent intent = new Intent(this, CMDJumpHereActivity.class);
					intent.putExtra("CMDCode", currentCMD + "");
					intent.putExtra("result", result);
					startActivity(intent);
				} else {
					Toast.makeText(CMDListActivity.this, "响应体不完整或数据校验失败", Toast.LENGTH_SHORT).show();
					isDownloadding = false;
				}
			} else if (currentCMD == Head.CMD_START_REMOTE_UPGRADE) {
				Toast.makeText(CMDListActivity.this, mDeviceName+"已收到升级命令", Toast.LENGTH_SHORT).show();
			}
		}


	}


	//------------ 以下是蓝牙通讯部分 ----------
	private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
				mConnected = true;
				//连接成功逻辑部分
				btn_connect.setVisibility(View.VISIBLE);
				btn_dis_connect.setVisibility(View.GONE);
			} else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
				mConnected = false;
				btn_connect.setVisibility(View.GONE);
				btn_dis_connect.setVisibility(View.VISIBLE);
				//断开连接逻辑部分
			} else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
				// 获取所有服务通道部分
				displayGattServices(mBluetoothLeService.getSupportedGattServices());
				Log.e(TAG, "onReceive: 开始获取服务和特征值……");
			} else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
				byte[] recive = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);

				receiveDownloadCMD(recive);
				/*//响应数据处理部分
				if (currentCMD != Head.CMD_REQUEST_DOWNLOAD_PACKAGE ||currentCMD != Head.CMD_START_REMOTE_UPGRADE ||currentCMD != Head.CMD_PACKAGE_DOWNLOAD_SUCCESSFULLY) {
					if (isTiming) { //只有在计时器走的时候才接收数据
						responseBuffer = ToolsUtils.concatAll(responseBuffer, recive);
						System.out.println("***********");
						System.out.println(Arrays.toString(responseBuffer));
					}
				} else {
					//外围设备请求体小于20B不用分包
					System.out.println("***********");
					System.out.println(Arrays.toString(recive));
					receiveDownloadCMD(recive);

				}*/


			}
		}

	};

	/**
	 * 解析gattServices，把server罗列到可扩展列表上
	 *
	 * @param gattServices
	 */
	private void displayGattServices(List<BluetoothGattService> gattServices) {
		if (gattServices == null) return;
		String uuid;

		// Loops through available GATT Services.
		for (BluetoothGattService gattService : gattServices) {
			uuid = gattService.getUuid().toString();
			//只过滤串口服务
			if (uuid.equals("0000fff0-0000-1000-8000-00805f9b34fb")) {
				Log.e("----gattService层-----", "displayGattServices: " + uuid);

				List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();
				ArrayList<BluetoothGattCharacteristic> charas = new ArrayList<>();

				// Loops through available Characteristics.
				for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
					charas.add(gattCharacteristic);
					uuid = gattCharacteristic.getUuid().toString();
					if (uuid.equals("0000fff3-0000-1000-8000-00805f9b34fb")) {
						final int charaProp = gattCharacteristic.getProperties();
						if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
							mWriteCharacteristic = gattCharacteristic;
							//此特征值变化的时候会启动广播通知
							mBluetoothLeService.setCharacteristicNotification(gattCharacteristic, true);
							break;

						}
					}
					/*//监听fff4通道
					if(uuid.equals("0000fff4-0000-1000-8000-00805f9b34fb")){
						final int charaProp = gattCharacteristic.getProperties();
						if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
							//此特征值变化的时候会启动广播通知
							mBluetoothLeService.setCharacteristicNotification(gattCharacteristic, true);
							break;

						}
					}*/
					Log.e("---Characteristic层----", "displayGattServices: " + uuid);
				}
			} else {//end for
				continue;
			}
		}

	}

	/**
	 * 广播过滤器
	 *
	 * @return
	 */
	private static IntentFilter makeGattUpdateIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
		intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
		return intentFilter;
	}

	@Override
	protected void onResume() {
		super.onResume();
		registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
		if (mBluetoothLeService != null) {
			final boolean result = mBluetoothLeService.connect(mDeviceAddress);
			Log.d(TAG, "Connect request result=" + result);
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		unregisterReceiver(mGattUpdateReceiver);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		unbindService(mServiceConnection);
		mBluetoothLeService = null;
	}


	//------------- 以下是数据生成部分 ---------

	/**
	 * 手机参数编码请求内容
	 *
	 * @param cmd
	 */
	private void makeCMD(byte cmd) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		LayoutInflater inflater = getLayoutInflater();

		switch (cmd) {
			case Head.CMD_TIME_SYNCHRONIZATION:
				//无需参数 直接loading界面
				Head syncHead = new Head(Head.CMD_TIME_SYNCHRONIZATION);
				BleMessage syncMsg = new BleMessage();
				sendMsg(cmd, syncMsg.encodeMessage(syncHead, new TimeSyncRequest()));
				break;
			case Head.CMD_QUERY_SYSTEM_INFORMATION:
				//无需参数 直接loading界面
				Head sysInfoHead = new Head(Head.CMD_QUERY_SYSTEM_INFORMATION);
				BleMessage sysInfoMsg = new BleMessage();
				sendMsg(cmd, sysInfoMsg.encodeMessage(sysInfoHead, new SysInfoRequest()));
				break;
			case Head.CMD_SET_VOLUME://音量设置--
				Head volHead = new Head(Head.CMD_SET_VOLUME);
				SetVolumeRequest setVolumeRequest = new SetVolumeRequest();
				showSetVolDialog(cmd, builder, inflater, volHead, setVolumeRequest);
				break;
			case Head.CMD_SET_CHARGE_MODE://充电模式
				Head modeHead = new Head(Head.CMD_SET_CHARGE_MODE);
				SetChargeModeRequest setChargeModeRequest = new SetChargeModeRequest();
				showSetChargeModeDialog(cmd, builder, inflater, modeHead, setChargeModeRequest);
				break;
			case Head.CMD_CHARGE_NOW://立即充电
				Head nowHead = new Head(Head.CMD_CHARGE_NOW);
				ChargeNowRequest chargeNowRequest = new ChargeNowRequest();
				showChargeNowDialog(cmd, builder, inflater, nowHead, chargeNowRequest);
				break;
			case Head.CMD_QUERY_STATE://查询状态 --
				Head stateHead = new Head(Head.CMD_QUERY_STATE);
				GunIdRequest stateRequest = new GunIdRequest();//枪号
				showSetGunIdDialog(cmd, "查询状态", builder, inflater, stateHead, stateRequest);
				break;
			case Head.CMD_END_CHARGE: //结束充电--
				Head endHead = new Head(Head.CMD_END_CHARGE);
				GunIdRequest endRequest = new GunIdRequest();//枪号
				showSetGunIdDialog(cmd, "结束充电", builder, inflater, endHead, endRequest);
				break;
			case Head.CMD_QUERY_CHARGING_HISTORY: //历史记录--
				//查询充电历史记录
				Head historyHead = new Head(Head.CMD_QUERY_CHARGING_HISTORY);
				//body部分需要参数
				GunIdRequest chargeHistoryRequest = new GunIdRequest();//枪号

				showSetGunIdDialog(cmd, "历史记录", builder, inflater, historyHead, chargeHistoryRequest);
				break;
			case Head.CMD_QUERY_CUMULATIVE_CHARGE://累计电量--
				Head cumulativeHead = new Head(Head.CMD_QUERY_CUMULATIVE_CHARGE);
				GunIdRequest cumulativeRequest = new GunIdRequest();//枪号
				showSetGunIdDialog(cmd, "累计电量", builder, inflater, cumulativeHead, cumulativeRequest);
				break;
			case Head.CMD_START_REMOTE_UPGRADE:
				fileblocks = null;
				Head remoteHead = new Head(Head.CMD_START_REMOTE_UPGRADE);
				RemoteUpgradeRequest remoteRequest = new RemoteUpgradeRequest();
				showRemoteDialog(cmd, "远程升级", builder, inflater, remoteHead, remoteRequest);
				break;
		}
	}

	/**
	 * 远程升级
	 *
	 * @param cmd
	 * @param name
	 * @param builder
	 * @param inflater
	 * @param remoteHead
	 * @param remoteRequest
	 */
	private void showRemoteDialog(final byte cmd, String name, AlertDialog.Builder builder,
								  LayoutInflater inflater, final Head remoteHead, final RemoteUpgradeRequest remoteRequest) {
		final View view = inflater.inflate(R.layout.dialog_remote_update, null);
		builder.setTitle(name);
		builder.setView(view);
		final EditText et_signal = (EditText) view.findViewById(R.id.et_signal);
		final EditText et_softVsersion = (EditText) view.findViewById(R.id.et_softVsersion);
		final EditText et_subsectionNum = (EditText) view.findViewById(R.id.et_subsectionNum);
		Button btn_file = (Button) view.findViewById(R.id.btn_file);
		btn_file.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				FileUtils.showFileChooser(CMDListActivity.this);
			}
		});

		builder.setPositiveButton("确认", new DialogInterface.OnClickListener() {
			byte signal;//客户端表示，升级程序对应的桩类型，不一致不能升级
			String softVsersion = "0.0.0";//软件版本号 1.00.01
			int packageLength;//升级包大小
			byte[] checksum;
			int subsectionNum;//分段字节数

			@Override
			public void onClick(DialogInterface dialog, int which) {
				if (fileblocks != null) {
					//获取文件块 ，文件块在onActivityResult回调中初始化
					checksum = ToolsUtils.fileCRC32(fileblocks);
					signal = (byte) Integer.parseInt(et_signal.getText().toString());
					softVsersion = et_softVsersion.getText().toString();
					packageLength = fileblocks.length;
					subsectionNum = Integer.parseInt(et_subsectionNum.getText().toString());

					BleMessage bleMessage = new BleMessage();
					remoteRequest.setChecksum(checksum);
					remoteRequest.setSignal(signal);
					remoteRequest.setPackageLength(packageLength);
					remoteRequest.setSubsectionNum(subsectionNum);
					remoteRequest.setSoftVsersion(softVsersion);
					sendMsg(cmd, bleMessage.encodeMessage(remoteHead, remoteRequest));
					isDownloadding = true;
				} else {
					Toast.makeText(CMDListActivity.this, "文件获取失败", Toast.LENGTH_SHORT).show();
				}
				dialog.dismiss();
			}
		});
		builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		builder.create().show();

	}

	/**
	 * 异步加载文件
	 * 给文件块赋值
	 */
	public class FileTask extends AsyncTask<String, Void, Integer> {
		static final int ERROR_FILE = 1;
		static final int SUCC = 2;

		@Override
		protected Integer doInBackground(String... strings) {

			try {
				File file = new File(strings[0]);
				InputStream inputStream = new FileInputStream(file);
				fileblocks = ToolsUtils.inputStreamTOByte(file, inputStream);
				inputStream.close();
			} catch (Exception e) {
				e.printStackTrace();
				return ERROR_FILE;
			}
			return SUCC;
		}

		@Override
		protected void onPostExecute(Integer flag) {
			switch (flag) {
				case ERROR_FILE:
					//文件打开失败
					fileSucc = false;
					break;
				case SUCC:
					fileSucc = true;
					//加载成功逻辑
					break;
			}
		}
	}

	/**
	 * 文件选择器回调
	 *
	 * @param requestCode
	 * @param resultCode
	 * @param data
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case FileUtils.FILE_SELECT_CODE:
				if (resultCode == RESULT_OK) {
					Uri uri = data.getData();
					filePath = FileUtils.getPath(this, uri);
					//异步加载文件
					new FileTask().execute(filePath);
				}
				break;
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	/**
	 * 设置枪编号
	 *
	 * @param cmd
	 * @param name
	 * @param builder
	 * @param inflater
	 * @param head
	 * @param gunIdRequest
	 */
	private void showSetGunIdDialog(final byte cmd, String name, final AlertDialog.Builder builder, LayoutInflater inflater, final Head head, final GunIdRequest gunIdRequest) {
		final View view = inflater.inflate(R.layout.dialog_gun_num, null);
		final EditText editText = (EditText) view.findViewById(R.id.et_gun_id);
		final TextView textView = (TextView) view.findViewById(R.id.tv_hex);
		editText.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {

			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				String temp = s.toString();
				if (s.length() != 0) {
					if (Integer.parseInt(temp) > 255) {
						temp = 255 + "";
						editText.setText("255");
					}
				}
				String hex = s.length() != 0 ? Integer.toHexString(Integer.parseInt(temp)) : "0";
				textView.setText("十六进制编号：0x" + (hex.length() == 1 ? "0" + hex.toUpperCase() : hex.toUpperCase()));
			}

			@Override
			public void afterTextChanged(Editable s) {

			}
		});
		builder.setTitle(name);
		builder.setView(view);
		builder.setPositiveButton("确认", new DialogInterface.OnClickListener() {
			int gunId = 0x0A;

			@Override
			public void onClick(DialogInterface dialog, int which) {
				if (editText.getText() != null) {
					gunId = Integer.parseInt(editText.getText().toString());

					BleMessage bleMessage = new BleMessage();
					gunIdRequest.setId(gunId);
					sendMsg(cmd, bleMessage.encodeMessage(head, gunIdRequest));

					dialog.dismiss();
				} else {
					Toast.makeText(CMDListActivity.this, "输入枪编号", Toast.LENGTH_SHORT).show();
				}
				dialog.dismiss();
			}
		});
		builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		builder.create().show();
	}


	/**
	 * 音量设置 参数设置弹窗
	 *
	 * @param cmd
	 * @param builder
	 * @param inflater
	 * @param volHead
	 * @param setVolumeRequest
	 */
	private void showSetVolDialog(final byte cmd, final AlertDialog.Builder builder, LayoutInflater inflater,
								  final Head volHead, final SetVolumeRequest setVolumeRequest) {
		View setVolume = inflater.inflate(R.layout.dialog_set_volume, null);
		builder.setView(setVolume);
		builder.setTitle("音量设置");
		//seekbar部分
		SeekBar seekBar = (SeekBar) setVolume.findViewById(R.id.id_seekbar);
		final TextView tv_volume = (TextView) setVolume.findViewById(R.id.tv_volume);
		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				tv_volume.setText(progress + "");
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {

			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {

			}
		});
		// 确认按钮
		builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				int vol = Integer.parseInt(tv_volume.getText().toString());
				BleMessage bleMessage = new BleMessage();
				setVolumeRequest.setVolume(vol);
				sendMsg(cmd, bleMessage.encodeMessage(volHead, setVolumeRequest));

				dialog.dismiss();
			}
		});
		//取消按钮
		builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		builder.create().show();
	}

	/**
	 * 充电模式 参数设置弹窗
	 *
	 * @param cmd
	 * @param builder
	 * @param inflater
	 * @param modeHead
	 * @param setChargeModeRequest
	 */
	private void showSetChargeModeDialog(final byte cmd, final AlertDialog.Builder builder, LayoutInflater inflater,
										 final Head modeHead, final SetChargeModeRequest setChargeModeRequest) {
		final View view = inflater.inflate(R.layout.dialog_set_charge_mode, null);

		builder.setView(view);
		builder.setTitle("设置充电模式");

		final LinearLayout wheel_start_time = (LinearLayout) view.findViewById(R.id.wheel_start_time);
		final LinearLayout wheel_end_time = (LinearLayout) view.findViewById(R.id.wheel_end_time);
		final WheelView startHourWheel, startMinuteWheel, endHourWheel, endMinuteWheel;

		startHourWheel = (WheelView) view.findViewById(R.id.start_hourwheel);
		startMinuteWheel = (WheelView) view.findViewById(R.id.start_minutewheel);
		endHourWheel = (WheelView) view.findViewById(R.id.end_hourwheel);
		endMinuteWheel = (WheelView) view.findViewById(R.id.end_minutewheel);

		Calendar calendar = Calendar.getInstance();
		int curHour = calendar.get(Calendar.HOUR_OF_DAY);
		int curMinute = calendar.get(Calendar.MINUTE);

		String[] hourContent, minuteContent;
		hourContent = new String[24];
		for (int i = 0; i < 24; i++) {
			hourContent[i] = String.valueOf(i);
			if (hourContent[i].length() < 2) {
				hourContent[i] = "0" + hourContent[i];
			}
		}

		minuteContent = new String[60];
		for (int i = 0; i < 60; i++) {
			minuteContent[i] = String.valueOf(i);
			if (minuteContent[i].length() < 2) {
				minuteContent[i] = "0" + minuteContent[i];
			}
		}

		startHourWheel.setAdapter(new StrericWheelAdapter(hourContent));
		startHourWheel.setCurrentItem(curHour);
		startHourWheel.setCyclic(true);
		startHourWheel.setInterpolator(new AnticipateOvershootInterpolator());

		startMinuteWheel.setAdapter(new StrericWheelAdapter(hourContent));
		startMinuteWheel.setCurrentItem(curMinute);
		startMinuteWheel.setCyclic(true);
		startMinuteWheel.setInterpolator(new AnticipateOvershootInterpolator());

		endHourWheel.setAdapter(new StrericWheelAdapter(hourContent));
		endHourWheel.setCurrentItem(curHour);
		endHourWheel.setCyclic(true);
		endHourWheel.setInterpolator(new AnticipateOvershootInterpolator());

		endMinuteWheel.setAdapter(new StrericWheelAdapter(hourContent));
		endMinuteWheel.setCurrentItem(curMinute);
		endMinuteWheel.setCyclic(true);
		endMinuteWheel.setInterpolator(new AnticipateOvershootInterpolator());

		final ChargeMode chargeMode = new ChargeMode();
		RadioGroup radioGroup = (RadioGroup) view.findViewById(R.id.radio_group);


		radioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {

			@Override
			public void onCheckedChanged(RadioGroup group, int checkedId) {
				RadioButton radioButton = (RadioButton) view.findViewById(group.getCheckedRadioButtonId());
				switch (radioButton.getText().toString()) {
					//第一字节保存小时，第二字节保存分钟
					//0表示不限时间
					case "经济模式":
						wheel_start_time.setVisibility(View.VISIBLE);
						wheel_end_time.setVisibility(View.VISIBLE);
						chargeMode.setMode(SetChargeModeRequest.MODE_BARGAIN);

						break;
					case "定时模式":
						wheel_start_time.setVisibility(View.VISIBLE);
						wheel_end_time.setVisibility(View.GONE);
						chargeMode.setMode(SetChargeModeRequest.MODE_TIMING);

						break;
					default://立即模式
						wheel_start_time.setVisibility(View.GONE);
						wheel_end_time.setVisibility(View.GONE);
						chargeMode.setMode(SetChargeModeRequest.MODE_IMMEDIATELY);
				}
			}
		});

		//确认按钮
		builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
			int startHour, endHour, startMinute, endMinute;

			@Override
			public void onClick(DialogInterface dialog, int which) {
				switch (chargeMode.getMode()) {

					case SetChargeModeRequest.MODE_BARGAIN:
						startHour = Integer.parseInt(startHourWheel.getCurrentItemValue());
						startMinute = Integer.parseInt(startMinuteWheel.getCurrentItemValue());
						endHour = Integer.parseInt(endHourWheel.getCurrentItemValue());
						endMinute = Integer.parseInt(endMinuteWheel.getCurrentItemValue());
						break;
					case SetChargeModeRequest.MODE_TIMING:
						startHour = Integer.parseInt(startHourWheel.getCurrentItemValue());
						startMinute = Integer.parseInt(startMinuteWheel.getCurrentItemValue());
						endHour = 0;
						endMinute = 0;
						break;
					case SetChargeModeRequest.MODE_IMMEDIATELY:
						startHour = 0;
						startMinute = 0;
						endHour = 0;
						endMinute = 0;
						break;
				}
				BleMessage bleMessage = new BleMessage();
				setChargeModeRequest.setChargeMode(chargeMode.getMode());
				setChargeModeRequest.setStartH(startHour);
				setChargeModeRequest.setStartM(startMinute);
				setChargeModeRequest.setEndH(endHour);
				setChargeModeRequest.setEndM(endMinute);
				sendMsg(cmd, bleMessage.encodeMessage(modeHead, setChargeModeRequest));
				dialog.dismiss();
			}
		});

		//取消按钮
		builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {

				dialog.dismiss();
			}
		});
		builder.create().show();

	}

	/**
	 * 立即充电 参数设置弹窗
	 *
	 * @param cmd
	 * @param builder
	 * @param inflater
	 * @param nowHead
	 * @param chargeNowRequest
	 */
	private void showChargeNowDialog(final byte cmd, final AlertDialog.Builder builder, LayoutInflater inflater,
									 final Head nowHead, final ChargeNowRequest chargeNowRequest) {
		final View view = inflater.inflate(R.layout.dialog_charge_now, null);
		builder.setView(view);
		builder.setTitle("立即充电");

		final RadioGroup radioGroup = (RadioGroup) view.findViewById(R.id.rg_charge_now);
		final EditText editText = (EditText) view.findViewById(R.id.et_gun_id);
		editText.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {

			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				String temp = s.toString();
				if (s.length() != 0) {
					if (Integer.parseInt(temp) > 255) {
						editText.setText("255");
					}
				}
			}

			@Override
			public void afterTextChanged(Editable s) {

			}
		});

		builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
			int authorizeType;
			int id;

			@Override
			public void onClick(DialogInterface dialog, int which) {
				RadioButton radioButton = (RadioButton) view.findViewById(radioGroup.getCheckedRadioButtonId());

				switch (radioButton.getText().toString()) {
					case "长期授权":
						authorizeType = 0x01;
						break;
					default://本次授权
						authorizeType = 0x00;
				}
				if (editText.getText() != null) {
					id = Integer.parseInt(editText.getText().toString());
					BleMessage bleMessage = new BleMessage();
					chargeNowRequest.setAuthorizeType(authorizeType);
					chargeNowRequest.setId(id);
					sendMsg(cmd, bleMessage.encodeMessage(nowHead, chargeNowRequest));
					dialog.dismiss();
				} else {
					Toast.makeText(CMDListActivity.this, "输入枪编号", Toast.LENGTH_SHORT).show();
				}
			}
		});

		builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {

				dialog.dismiss();
			}
		});
		builder.create().show();
	}


	@Override
	public void onClick(View v) {
		switch (v.getId()) {
			case R.id.btn_dis_connect:
//				btn_dis_connect.setVisibility(View.GONE);
//				btn_connect.setVisibility(View.VISIBLE);
//				mBluetoothLeService.connect(mDeviceAddress);
				isDownloadding = false;
				break;
			case R.id.btn_connect:
				isDownloadding = false;
//				btn_dis_connect.setVisibility(View.VISIBLE);
//				btn_connect.setVisibility(View.GONE);
//				mBluetoothLeService.disconnect();
				break;
		}
	}
}
