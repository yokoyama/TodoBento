/*
 * Copyright (C) 2012 Kazuya (Kaz) Yokoyama <kazuya.yokoyama@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mobisocial.bento.todo.io;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mobisocial.bento.todo.ui.BentoListItem;
import mobisocial.bento.todo.ui.TodoListItem;
import mobisocial.bento.todo.util.BitmapHelper;
import mobisocial.bento.todo.util.UIUtils;
import mobisocial.socialkit.Obj;
import mobisocial.socialkit.musubi.DbFeed;
import mobisocial.socialkit.musubi.DbIdentity;
import mobisocial.socialkit.musubi.DbObj;
import mobisocial.socialkit.musubi.FeedObserver;
import mobisocial.socialkit.musubi.Musubi;
import mobisocial.socialkit.musubi.Musubi.DbThing;
import mobisocial.socialkit.musubi.multiplayer.FeedRenderable;
import mobisocial.socialkit.obj.MemObj;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;


public class BentoManager {
    public interface OnStateUpdatedListener {
        public void onStateUpdated();
    }

	public static final String TYPE_TODOBENTO = "todobento";
	public static final String TYPE_APPSTATE = "appstate";
	public static final String FEED_NAME = "name";

	// root > state
	public static final String STATE = "state";
	// root > state > bento
	public static final String VERSION_CODE = "version_code";
	public static final String BENTO = "bento";
	public static final String BENTO_UUID = "uuid";
	public static final String BENTO_NAME = "name";
	public static final String BENTO_CRE_CONTACT_ID = "cre_contact_id";
	// root > state > bento > todo array
	public static final String TODO_LIST = "list";
	public static final String TODO_UUID = "uuid";
	public static final String TODO_TITLE = "title";
	public static final String TODO_DESCRIPTION = "description";
	public static final String TODO_HAS_IMG = "has_image";
	public static final String TODO_DONE = "done";
	public static final String TODO_CRE_DATE = "cre_date";
	public static final String TODO_MOD_DATE = "mod_date";
	public static final String TODO_CRE_CONTACT_ID = "cre_contact_id";
	public static final String TODO_MOD_CONTACT_ID = "mod_contact_id";
	// root > state > todo_image
	public static final String TODO_IMAGE = "todo_image";
	public static final String TODO_IMAGE_UUID = "todo_image_uuid";
	public static final String B64JPGTHUMB = FeedRenderable.OBJ_B64_JPEG;
	
	private class LatestObj {
		public JSONObject json = null;
		public int intKey = 0;
	};
	private static final Boolean DEBUG = UIUtils.isDebugMode();
	private static final String TAG = "TodoDataManager";
	private static BentoManager sInstance = null;
	private Musubi mMusubi = null;
	private Uri mCurrentUri = null;
	private String mLocalContactId = null;
	private String mLocalName = null;
    private Integer mLastInt = 0;
    private int mVersionCode = 0;
    private boolean mbFromMusubi = false;
    
	private ArrayList<BentoListItem> mBentoList = new ArrayList<BentoListItem>();
	private BentoListItem mBento = new BentoListItem();
	private Map<Long, ArrayList<String>> mMemberNameCache = new HashMap<Long, ArrayList<String>>();
    private ArrayList<OnStateUpdatedListener> mListenerList = new ArrayList<OnStateUpdatedListener>();

	// ----------------------------------------------------------
	// Instance
	// ----------------------------------------------------------
	private BentoManager() {
		mBento = null;
		mMemberNameCache = null;
	}

	public static BentoManager getInstance() {
		if (sInstance == null) {
			sInstance = new BentoManager();
		}

		return sInstance;
	}
	
	public void init(Musubi musubi) {
		if (DEBUG) Log.d(TAG, "init()");
		
		// remove previous data
		if (mCurrentUri != null) {
			musubi.objForUri(mCurrentUri).getSubfeed().unregisterStateObserver(mStateObserver);
		}
		mCurrentUri = null;
		mBentoList = new ArrayList<BentoListItem>();
		mBento = null;
		mMemberNameCache = null;
	}

	public void setMusubi(Musubi musubi, int versionCode) {
		if (DEBUG) Log.d(TAG, "setMusubi()");
		
		mMusubi = musubi;
		mVersionCode = versionCode;
		
		// start new one
		if (mMusubi.getObj() != null && mMusubi.getObj().getSubfeed() != null) {
			setBentoObjUri(mMusubi.getObj().getUri());
		}
	}

	// ----------------------------------------------------------
	// Get / Retrieve
	// ----------------------------------------------------------
	synchronized public boolean hasBento() {
		return (mBento != null);
	}
	
	synchronized public BentoListItem getBentoListItem() {
		return mBento;
	}
	
	synchronized public TodoListItem getTodoListItem(int position) {
		return mBento.bento.todoList.get(position);
	}
	
	synchronized public TodoListItem getTodoListItem(String uuid) {
		for (int i = 0; i < mBento.bento.todoList.size(); i++) {
			TodoListItem item = mBento.bento.todoList.get(i);
			if (item.uuid.equals(uuid)) {
				return item;
			}
		}
		
		return null;
	}

	synchronized public int getTodoListCount() {
		if (mBento != null) {
			return mBento.bento.todoList.size();
		} else {
			return 0;
		}
	}
	
	synchronized public Bitmap getTodoBitmap(String todoUuid,
			int targetWidth, int targetHeight, float degrees) {
		return getTodoBitmap(mCurrentUri, todoUuid, targetWidth, targetHeight, degrees);
	}
	
	synchronized public Bitmap getTodoBitmap(Uri objUri, String todoUuid,
			int targetWidth, int targetHeight, float degrees) {
		Bitmap bitmap = null;
		
		DbFeed dbFeed = mMusubi.objForUri(objUri).getSubfeed();

		Cursor c = dbFeed.query();
		c.moveToFirst();
		for (int i = 0; i < c.getCount(); i++) {
			Obj object = mMusubi.objForCursor(c);
			if (object != null && object.getJson() != null && object.getJson().has(TODO_IMAGE)) {
				JSONObject diff = object.getJson().optJSONObject(TODO_IMAGE);
				if (todoUuid.equals(diff.optString(TODO_IMAGE_UUID))) {
					byte[] byteArray = Base64.decode(object.getJson().optString(B64JPGTHUMB), Base64.DEFAULT);
					bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);
					break;
				}
			}
			c.moveToNext();
		}
		c.close();

		if (bitmap != null) {
			bitmap = BitmapHelper.getResizedBitmap(bitmap, targetWidth, targetHeight, degrees);
		}

		return bitmap;
	}

	synchronized public String getLocalContactId() {
		return mLocalContactId;
	}

	synchronized public String getLocalName() {
		return mLocalName;
	}

	// Bento List
	synchronized public void loadBentoList() {
		long prevFeedId = -1;
        String[] projection = new String[] { DbObj.COL_ID, DbObj.COL_FEED_ID };
        Uri uri = Musubi.uriForDir(DbThing.OBJECT);
        String selection = "type = ?";
        String[] selectionArgs = new String[] { TYPE_TODOBENTO };
        String sortOrder = DbObj.COL_FEED_ID + " asc, " + DbObj.COL_LAST_MODIFIED_TIMESTAMP + " asc";
        
		Cursor c = mMusubi.getContext().getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);

		ArrayList<BentoListItem> tmpList = new ArrayList<BentoListItem>();
		if (c != null && c.moveToFirst()) {
			mBentoList = new ArrayList<BentoListItem>();
			
			for (int i = 0; i < c.getCount(); i++) {
				BentoListItem item = new BentoListItem();
				item.bento = new Bento();
				
				DbObj dbObj = mMusubi.objForCursor(c);
				LatestObj latestObj = null;
				
				latestObj = fetchLatestObj(c.getLong(0));
				if (latestObj != null && latestObj.json.has(STATE)) {
					JSONObject stateObj = latestObj.json.optJSONObject(STATE);
					if (fetchBentoObj(stateObj, item.bento)) {
						item.objUri = dbObj.getUri();
						item.feedId = c.getLong(1);
						if (DEBUG) {
							Log.d(TAG, item.objUri.toString());
							Log.d(TAG, item.feedId + "");
						}
					
						// count number of todo
						ArrayList<TodoListItem> todoList = new ArrayList<TodoListItem>();
						if (fetchTodoListObj(stateObj, todoList)) {
							item.bento.numberOfTodo = todoList.size();
							tmpList.add(0, item);
						}
						
						// load members
						fetchMemberNames(item.feedId);
					}
				}
				c.moveToNext();
			}
		}
		c.close();
		
		// insert dividers
		if (tmpList.size() > 0) {
			for (int j = 0; j < tmpList.size(); j++) {
				BentoListItem item = tmpList.get(j);
				if (prevFeedId != item.feedId) {
					BentoListItem divider = new BentoListItem();
					divider.enabled = false;
					divider.feedId = item.feedId;
					mBentoList.add(divider);
					
					prevFeedId = item.feedId;
				}
				mBentoList.add(item);
			}
		}
		
		if (DEBUG) {
			Log.d(TAG, "tmpList:" + tmpList.size() + " mBentoList:" + mBentoList.size());
		}
	}
	
	private LatestObj fetchLatestObj(long localId) {
        Uri uri = Musubi.uriForDir(DbThing.OBJECT);
        String[] projection = new String[] { DbObj.COL_JSON, DbObj.COL_INT_KEY };
        String selection = DbObj.COL_PARENT_ID + "=? and type= ?";
        String[] selectionArgs = new String[] { Long.toString(localId), TYPE_APPSTATE };
        String sortOrder = DbObj.COL_INT_KEY + " desc limit 1";
        Cursor c = mMusubi.getContext().getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
        try {
            if (c.moveToFirst()) {
            	LatestObj latestObj = new LatestObj();
            	try {
					latestObj.json = new JSONObject(c.getString(0));
				} catch (JSONException e) {
					e.printStackTrace();
				}
            	latestObj.intKey = c.getInt(1);
                return latestObj;
            } else {
                c.close();
                selection = DbObj.COL_ID + "=?";
                selectionArgs = new String[] { Long.toString(localId) };
                c = mMusubi.getContext().getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
                if (c.moveToFirst()) {
                	LatestObj latestObj = new LatestObj();
                	try {
						latestObj.json = new JSONObject(c.getString(0));
					} catch (JSONException e) {
						e.printStackTrace();
					}
                	latestObj.intKey = c.getInt(1);
                    return latestObj;
                } else {
                    return null;
                }
            }
        } finally {
            c.close();
        }
    }
	
	synchronized public BentoListItem getBentoListItem(int position) {
		return mBentoList.get(position);
	}

	synchronized public int getBentoListCount() {
		return mBentoList.size();
	}
	
	public ArrayList<String> getMemberNames(long feedId) {
		if (mMemberNameCache == null || !mMemberNameCache.containsKey(feedId)) {
			fetchMemberNames(feedId);
		}
		return mMemberNameCache.get(feedId);
	}
	
	private void fetchMemberNames(long feedId) {
		if (mMemberNameCache == null) {
			mMemberNameCache = new HashMap<Long, ArrayList<String>>();
		}
		
		if (!mMemberNameCache.containsKey(feedId)) {
			ArrayList<String> names = new ArrayList<String>();
	        Uri feedUri = Musubi.uriForItem(DbThing.FEED, feedId);
	        
	    	List<DbIdentity> members = mMusubi.getFeed(feedUri).getMembers();
	        for (int i = 0; i < members.size(); i++) {
	            DbIdentity id = members.get(i);
	            if (id != null) {
	            	if (!id.isOwned()) {
	            		// skip me
	            		names.add(id.getName());
	            	}
	            }
	        }
	        
	        mMemberNameCache.put(feedId, names);
		}
	}

	// ----------------------------------------------------------
	// Update
	// ----------------------------------------------------------
	synchronized public void createBento(Bento bento, String msg) {
		mBento = new BentoListItem();
		mBento.bento = bento;
		pushUpdate(msg, true);
	}
	
	synchronized public void addTodo(TodoListItem item, Bitmap image, String msg) {
		mBento.bento.todoList.add(0, item);
		
		if (image == null) {
			pushUpdate(msg);
		} else {
			String data = Base64.encodeToString(BitmapHelper.bitmapToBytes(image), Base64.DEFAULT);
			pushUpdate(msg, item.uuid, data, false);
		}
	}

	synchronized public void removeTodo(TodoListItem item, String msg) {
		// not supported yet
	}

	synchronized public void updateTodo(TodoListItem updateItem, String msg) {
		for (int i=0; i<mBento.bento.todoList.size(); i++) {
			TodoListItem item = mBento.bento.todoList.get(i);
			if (item.uuid.equals(updateItem.uuid)) {
				mBento.bento.todoList.remove(i);
				mBento.bento.todoList.add(i, updateItem);
				break;
			}
		}

		pushUpdate(msg);
	}

	synchronized public void sortTodoList(int positionFrom, int positionTo) {
		TodoListItem item = mBento.bento.todoList.get(positionFrom);
		
		// debug
		if (DEBUG) {
			Log.d(TAG, "sortTodoList:BEFORE");
			for (int i=0; i<mBento.bento.todoList.size(); i++) {
				TodoListItem debugItem = mBento.bento.todoList.get(i);
				Log.d(TAG, i + ":" + debugItem.title);
			}
		}
		
		if (positionFrom < positionTo) {
			mBento.bento.todoList.add((positionTo + 1), item);
			mBento.bento.todoList.remove(positionFrom);
		} else if (positionFrom > positionTo) {
			mBento.bento.todoList.remove(positionFrom);
			mBento.bento.todoList.add(positionTo, item);
		}

		// debug
		if (DEBUG) {
			Log.d(TAG, "sortTodoList:AFTER");
			for (int i=0; i<mBento.bento.todoList.size(); i++) {
				TodoListItem debugItem = mBento.bento.todoList.get(i);
				Log.d(TAG, i + ":" + debugItem.title);
			}
		}
	}

	synchronized public void sortTodoCompleted(String msg) {
		pushUpdate(msg);
	}

	synchronized public void clearTodoDone(String msg) {
		int beforeCount = mBento.bento.todoList.size();
		if (beforeCount == 0) {
			return;
		}
		
		// remove backward
		for (int i = (beforeCount - 1); i >= 0; i--) {
			TodoListItem item = mBento.bento.todoList.get(i);
			if (item.bDone) {
				mBento.bento.todoList.remove(i);
			}
		}
		
		// if updated
		if (beforeCount > mBento.bento.todoList.size()) {
			pushUpdate(msg);
		}
	}

	// ----------------------------------------------------------
	// Listener
	// ----------------------------------------------------------
	public void addListener(OnStateUpdatedListener listener){
		mListenerList.add(listener);
    }
	
	public void removeListener(OnStateUpdatedListener listener){
		mListenerList.remove(listener);
    }

	// ----------------------------------------------------------
	// Musubi
	// ----------------------------------------------------------
	public void setFromMusubi(boolean bFromMusubi) {
		mbFromMusubi = bFromMusubi;
	}

	public boolean isFromMusubi() {
		return mbFromMusubi;
	}
	
	public void setFeedUri(Uri feedUri) {
		mMusubi.setFeed(mMusubi.getFeed(feedUri));
	}
	
	public void pushUpdate(String msg) {
		pushUpdate(msg, null, null, false);
	}
	
	public void pushUpdate(String msg, boolean bFirst) {
		pushUpdate(msg, null, null, bFirst);
	}

	public void pushUpdate(String msg, String todoUuid, String data, boolean bFirst) {
		try {
			JSONObject rootObj = new JSONObject();
			rootObj.put(Obj.FIELD_RENDER_TYPE, Obj.RENDER_LATEST);
			rootObj.put(STATE, getStateObj());
			if (DEBUG) Log.d(TAG, "pushUpdate - getStateObj():" + getStateObj().toString());
			
			JSONObject out = new JSONObject(rootObj.toString());

			if (todoUuid != null && data != null) {
				JSONObject todoImageObj = new JSONObject();
				todoImageObj.put(TODO_IMAGE_UUID, todoUuid);
				out.put(TODO_IMAGE, todoImageObj);
				out.put(B64JPGTHUMB, data);
			}
			
			FeedRenderable renderable = FeedRenderable.fromText(msg);
			renderable.addToJson(out);
			if (bFirst) {
				Obj obj = new MemObj(TYPE_TODOBENTO, out, null);
				Uri bentoUri = mMusubi.getFeed().insert(obj);
				setBentoObjUri(bentoUri);
			} else {
				Obj obj = new MemObj(TYPE_APPSTATE, out, null, ++mLastInt);
				mMusubi.objForUri(mCurrentUri).getSubfeed().postObj(obj);
			}
		} catch (JSONException e) {
			Log.e(TAG, "Failed to post JSON", e);
		}
	}

	public void setBentoObjUri(Uri objUri) {
		// previous uri
		if (mCurrentUri != null) {
			mMusubi.objForUri(mCurrentUri).getSubfeed().unregisterStateObserver(mStateObserver);
		}
        
        // new uri
		mCurrentUri = objUri;
		
		DbObj dbObj = mMusubi.objForUri(objUri);
		Long localId = dbObj.getLocalId();
		
		LatestObj latestObj = null;
		latestObj = fetchLatestObj(localId);
		if (latestObj != null && latestObj.json.has(STATE)) {
			JSONObject stateObj = latestObj.json.optJSONObject(STATE);

			if (stateObj == null) {
				mBento = null;
				mLastInt = 0;
			} else {
				setNewStateObj(stateObj);
				mLastInt = latestObj.intKey;
			}
		}

		Uri feedUri = mMusubi.objForUri(mCurrentUri).getSubfeed().getUri();
		mLocalContactId = mMusubi.userForLocalDevice(feedUri).getId();
		mLocalName = mMusubi.userForLocalDevice(feedUri).getName();
		
		mMusubi.objForUri(mCurrentUri).getSubfeed().registerStateObserver(mStateObserver);
	}
	
	private final FeedObserver mStateObserver = new FeedObserver() {
		@Override
		public void onUpdate(DbObj obj) {
			if (DEBUG) Log.d(TAG, "onUpdate:" + obj.toString());
			
			// ignore
			if (obj == null || obj.getJson() == null || !obj.getJson().has(STATE)) {
				if (DEBUG) Log.d(TAG, "onUpdate: ignore-1");
				return;
			}

			// notify refresh (always)
			Handler handler = new Handler();
			handler.post(new Runnable(){
				public void run(){
					for (OnStateUpdatedListener listener : mListenerList) {
						listener.onStateUpdated();
					}
				}
			});
			
			if (mBento == null || mBento.bento == null) {
				if (DEBUG) Log.d(TAG, "onUpdate: ignore-2");
				return;
			}
			
			JSONObject stateObj = null;
			stateObj = obj.getJson().optJSONObject(STATE);
			try {
				if (!isValidBento(stateObj.getJSONObject(BENTO).optString(BENTO_UUID))) {
					if (DEBUG) Log.d(TAG, "onUpdate: ignore-3");
					return;
				}
			} catch (JSONException e) {
				Log.e(TAG, "Failed to get JSON", e);
				return;
			}

			// set new state
			setNewStateObj(stateObj);
			
			mLastInt = (obj.getIntKey() == null) ? 0 : obj.getIntKey();
			if (DEBUG) Log.d(TAG, "onUpdate - mLastInt: " + mLastInt);

		}
		
	};

	// ----------------------------------------------------------
	// Private
	// ----------------------------------------------------------
	private void setNewStateObj(JSONObject stateObj) {
		mBento = new BentoListItem();
		setNewBentoObj(stateObj);
		setNewTodoListObj(stateObj);
	}
	
	private void setNewBentoObj(JSONObject stateObj) {
		fetchBentoObj(stateObj, mBento.bento);
	}
	
	private boolean fetchBentoObj(JSONObject stateObj, Bento bento) {
		boolean ret = false;
		try {
			JSONObject bentoObj = stateObj.getJSONObject(BENTO);
			bento.uuid = bentoObj.optString(BENTO_UUID);
			bento.name = bentoObj.optString(BENTO_NAME);
			bento.creContactId = bentoObj.optString(BENTO_CRE_CONTACT_ID);
			
			ret = true;
		} catch (JSONException e) {
			Log.e(TAG, "Failed to get JSON", e);
		}
		
		return ret;
	}
	
	private void setNewTodoListObj(JSONObject stateObj) {
		mBento.bento.todoList = new ArrayList<TodoListItem>();
		fetchTodoListObj(stateObj, mBento.bento.todoList);
	}

	private boolean fetchTodoListObj(JSONObject stateObj, ArrayList<TodoListItem> todoList) {
		boolean ret = false;
		try {
			JSONArray todoListArray = stateObj.optJSONArray(TODO_LIST);
			
			if (todoListArray != null) {
				
				for (int i=0; i<todoListArray.length(); i++) {
					JSONObject todoObj = todoListArray.getJSONObject(i);
					TodoListItem item = new TodoListItem();
					item.uuid = todoObj.optString(TODO_UUID);
					item.title = todoObj.optString(TODO_TITLE);
					item.description = todoObj.optString(TODO_DESCRIPTION);
					item.hasImage = todoObj.optBoolean(TODO_HAS_IMG);
					item.bDone = todoObj.optBoolean(TODO_DONE);
					item.creDateMillis = todoObj.optLong(TODO_CRE_DATE);
					item.modDateMillis = todoObj.optLong(TODO_MOD_DATE);
					item.creContactId = todoObj.optString(TODO_CRE_CONTACT_ID);
					item.modContactId = todoObj.optString(TODO_MOD_CONTACT_ID);
					
					todoList.add(item);
				}
				ret = true;
			}
			
		} catch (JSONException e) {
			Log.e(TAG, "Failed to get JSON", e);
		}
		
		return ret;
	}
	
	private JSONObject getBentoObj() {
		
		JSONObject bentoObj = new JSONObject();
		try {
			bentoObj.put(BENTO_UUID, mBento.bento.uuid);
			bentoObj.put(BENTO_NAME, mBento.bento.name);
			bentoObj.put(BENTO_CRE_CONTACT_ID, mBento.bento.creContactId);

		} catch (JSONException e) {
			Log.e(TAG, "Failed to put JSON", e);
		}
		
		return bentoObj;
	}
	
	private JSONArray getTodoListArray() {
		JSONArray todoListArray = new JSONArray();

		try {

			for (int i = 0; i < mBento.bento.todoList.size(); i++) {
				TodoListItem item = mBento.bento.todoList.get(i);
				
				JSONObject todoObj = new JSONObject();
				todoObj.put(TODO_UUID, item.uuid);
				todoObj.put(TODO_TITLE, item.title);
				todoObj.put(TODO_DESCRIPTION, item.description);
				todoObj.put(TODO_HAS_IMG, item.hasImage);
				todoObj.put(TODO_DONE, item.bDone);
				todoObj.put(TODO_CRE_DATE, item.creDateMillis);
				todoObj.put(TODO_MOD_DATE, item.modDateMillis);
				todoObj.put(TODO_CRE_CONTACT_ID, item.creContactId);
				todoObj.put(TODO_MOD_CONTACT_ID, item.modContactId);
				
				todoListArray.put(todoObj);
			}
						
		} catch (JSONException e) {
			Log.e(TAG, "Failed to put JSON", e);
		}
		
		return todoListArray;
	}

	private JSONObject getStateObj() {
		JSONObject stateObj = new JSONObject();
		try {
			stateObj.put(VERSION_CODE, mVersionCode);
			JSONObject bentoObj = getBentoObj();
			stateObj.put(BENTO, bentoObj);
			JSONArray todoListArray = getTodoListArray();
			stateObj.put(TODO_LIST, todoListArray);
		} catch (JSONException e) {
			Log.e(TAG, "Failed to put JSON", e);
		}
		return stateObj;
	}
	
	private boolean isValidBento(String uuid) {
		return (mBento != null && mBento.bento.uuid != null && uuid != null && mBento.bento.uuid.equals(uuid));
	}
}
