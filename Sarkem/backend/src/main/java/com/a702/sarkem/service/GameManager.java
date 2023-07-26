package com.a702.sarkem.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.a702.sarkem.model.player.RolePlayer;
import org.redisson.misc.Hash;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import com.a702.sarkem.exception.GameOptionSettingException;
import com.a702.sarkem.exception.GameRoomNotFoundException;
import com.a702.sarkem.model.GameOptionDTO;
import com.a702.sarkem.model.game.GameSession;
import com.a702.sarkem.model.game.message.SystemMessage;
import com.a702.sarkem.model.game.message.SystemMessage.SystemCode;
import com.a702.sarkem.model.gameroom.GameRoom;
import com.a702.sarkem.model.player.Player;
import com.a702.sarkem.redis.ChatPublisher;
import com.a702.sarkem.redis.ChatSubscriber;
import com.a702.sarkem.redis.GamePublisher;
import com.a702.sarkem.redis.SystemSubscriber;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class GameManager {

	private final Map<String, GameRoom> gameRoomMap; // Key: roomId
	private final Map<String, GameSession> gameSessionMap; // Key: roomId

	// 채팅방(topic)에 발행되는 메시지를 처리할 Listner
	private final RedisMessageListenerContainer redisMessageListener;
	// redis pub/sub
	private final SystemSubscriber systemSubscriber;
	private final GamePublisher gamePublisher;
	private final ChatSubscriber chatSubscriber;
	private final ChatPublisher chatPublisher;
	// topic 관리
	private Map<String, ChannelTopic> topics = new HashMap<>();

	private ChannelTopic getGameTopic(String roomId) {
		return topics.get("GAME_" + roomId);
	}

	private ChannelTopic getChatTopic(String roomId) {
		return topics.get("CHAT_" + roomId);
	}

	/**
	 * 게임코드 랜덤 생성(16진수 5자리)
	 */
	public String gameCodeGenerate() {
		char[] tmp = new char[5];
		for (int i = 0; i < tmp.length; i++) {
			int div = (int) Math.floor(Math.random() * 2);

			if (div == 0) { // 0이면 숫자로
				tmp[i] = (char) (Math.random() * 10 + '0');
			} else { // 1이면 알파벳
				tmp[i] = (char) (Math.random() * 26 + 'A');
			}
		}
		return new String(tmp);
	}

	/**
	 * 게임방 생성
	 * 
	 * @param roomId
	 */
	public void createGameRoom(String roomId) {
		// 게임방, 게임 세션 생성
		GameRoom newGameRoom = new GameRoom(roomId); // 게임룸 생성
		gameRoomMap.put(roomId, newGameRoom);
		String newGameId = gameCodeGenerate(); // 새 게임 코드 획득
		newGameRoom.setGameId(newGameId);
		GameSession newGame = new GameSession(roomId, newGameId); // 새 게임 세션 생성
		gameSessionMap.put(roomId, newGame);

		// reids topic 생성
		String strRoomTopic = "GAME_" + roomId;
		String strChatTopic = "CHAT_" + roomId;
		ChannelTopic roomTopic = new ChannelTopic(strRoomTopic);
		ChannelTopic chatTopic = new ChannelTopic(strChatTopic);
		topics.put(strRoomTopic, roomTopic);
		topics.put(strChatTopic, chatTopic);
		redisMessageListener.addMessageListener(systemSubscriber, roomTopic);
		redisMessageListener.addMessageListener(chatSubscriber, chatTopic);
	}

	// 플레이어 나가기
	public void deletePlayer(String roomId, String playerId) {
		GameRoom gameRoom = gameRoomMap.get(roomId);
		gameRoom.deletePlayer(playerId);

		// 더 이상 게임 세션에 남은 인원이 없을 때
		if (gameRoom.getPlayerCount() == 0) {
			// gameRoom 정보와 redis topic 삭제
			gameRoomMap.remove(roomId);
			gameSessionMap.remove(roomId);
			topics.remove(getGameTopic(roomId));
			topics.remove(getChatTopic(roomId));
		}else if (getHostId(roomId).equals(playerId)) {
			// 방장 변경하기
			setHostId(roomId, gameRoom.getPlayersId().get(0));
			System.out.println("방장이 변경되었습니다." + playerId + " " + getHostId(roomId));
		}
	}

	// 호스트 아이디 지정해주기
	public void setHostId(String roomId, String playerId) {
		GameRoom gameRoom = gameRoomMap.get(roomId);
		gameRoom.setHostId(playerId);
		gameRoomMap.put(roomId, gameRoom);
	}

	// 현재 게임 세션 호스트 호출하기
	public String getHostId(String roomId) {
		GameRoom gameRoom = gameRoomMap.get(roomId);
		return gameRoom.getHostId();
	}

	/**
	 * 게임룸 정보 조회
	 */
	public GameRoom getGameRoom(String roomId) {
		return gameRoomMap.get(roomId);
	}

	/**
	 * 게임방 방장 여부 확인
	 */
	public boolean isHost(String roomId, String playerId) {
		if (playerId == null)
			return false;

		GameRoom gameRoom = gameRoomMap.get(roomId);
		String hostId = gameRoom.getHostId();
		if (!playerId.equals(hostId))
			return false;

		return true;
	}

	/**
	 * 플레이어 게임방 연결
	 */
	public void connectPlayer(String roomId, Player player) {
		GameRoom gameRoom = gameRoomMap.get(roomId);
		List<Player> playerList = gameRoom.getPlayers();
		playerList.add(player);
		if (gameRoom.getHostId() == null)
			gameRoom.setHostId(player.getPlayerId());
	}

	/**
	 * 게임방 정보 조회
	 * 
	 * @param roomId
	 * @return GameRoom 객체
	 */
	public GameRoom retrieveGameRoom(String roomId) throws GameRoomNotFoundException {
		GameRoom gameRoom = gameRoomMap.get(roomId);
		if (gameRoom == null)
			throw new GameRoomNotFoundException(roomId + " 게임방을 찾을 수 없습니다.");

		return gameRoomMap.get(roomId);
	}

	/**
	 * 게임 설정 변경
	 */
	public void gameOptionChange(String roomId, String playerId, GameOptionDTO option) {
				
		GameRoom room = gameRoomMap.get(roomId);
		// 방장과 플레이어 일치 여부 확인
		List<String> target = new ArrayList<>();
		target.add(playerId);
		if(!room.getHostId().equals(playerId)) {
			sendSystemMessage(roomId, target, SystemCode.ONLY_HOST_ACTION, null);
			return;
		}
		
		// 플레이어 수와 역할 수 일치 여부 확인
		int playerCount = room.getPlayerCount();
		int optionRoleCount = option.getTotalRoleCount();
		String hostId = room.getHostId();
		if (playerCount != optionRoleCount) {
			sendNoticeMessageToPlayer(roomId, hostId, "플래이어 수와 역할 수가 일치하지 않습니다.");
			return;
		}
			
		// 회의 시간 변경
		int meetingTime = option.getMeetingTime();
		if (meetingTime < 15 || meetingTime > 180) {
			sendNoticeMessageToPlayer(roomId, hostId, "회의 시간은 15s ~ 180s 사이로 설정 가능합니다.");
			return;
		}
		
		// redis 메시지 전송
		sendGameOptionChangedMessage(roomId, option);
	}

	/**
	 * 게임 시작 ======= 게임 쓰레드 시작
	 */
	public void gameStart(String roomId) {
		// 게임 러너 생성 및 시작
		GameRoom gameRoom = gameRoomMap.get(roomId);
		GameSession gameSession = gameSessionMap.get(roomId);
		ChannelTopic gameTopic = topics.get("GAME_" + roomId);
		ChannelTopic chatTopic = topics.get("CHAT_" + roomId);
		GameThread gameThread = new GameThread(this, gameRoom, gameSession, gameTopic, chatTopic);
		gameThread.run();
	}

	/**
	 * 시스템 메시지를 대상에게 전송
	 * @param roomId
	 * @param targets
	 * @param code
	 * @param param
	 */
	public void sendSystemMessage(String roomId, List<String> targets, SystemCode code, Object param) {
		ChannelTopic gameTopic = getGameTopic(roomId);
		for (String target : targets) {
			SystemMessage systemMessage = new SystemMessage(code, roomId, target, param);
			gamePublisher.publish(gameTopic, systemMessage);
		}
	}

	/**
	 * 시스템 메시지를 모두에게 전송
	 * 
	 * @param roomId
	 * @param code
	 * @param param
	 */
	public void sendSystemMessageToAll(String roomId, SystemCode code, Object param) {
		GameRoom gameRoom = gameRoomMap.get(roomId);
		List<Player> players = gameRoom.getPlayers();
		ChannelTopic gameTopic = getGameTopic(roomId);
		for (Player target : players) {
			SystemMessage systemMessage = new SystemMessage(code, roomId, target.getPlayerId(), param);
			gamePublisher.publish(gameTopic, systemMessage);
		}
	}

	// 0. 공통기능
	/**
	 * "안내메시지" 메시지를 대상에게 전송
	 * 
	 * @param playerId
	 * @param message
	 */
	public void sendNoticeMessageToPlayer(String roomId, String playerId, String message) {
		ChannelTopic gameTopic = getGameTopic(roomId);
		HashMap<String, String> param = new HashMap<>();
		param.put("message", message);
		List<String> targets = new ArrayList<>();
		targets.add(playerId);
		sendSystemMessage(roomId, targets, SystemCode.NOTICE_MESSAGE, param);
	}

	/**
	 * "안내메시지" 메시지를 대상들에게 전송
	 * 
	 * @param playersId
	 * @param message
	 */
	public void sendNoticeMessageToPlayers(String roomId, String[] playersId, String message) {
		
	}

	/**
	 * "안내메시지" 메시지를 모두에게 전송
	 * 
	 * @param message
	 */
	public void sendNoticeMessageToAll(String roomId, String message) {
		HashMap<String, String> param = new HashMap<>();
		param.put("message", message);
		sendSystemMessageToAll(roomId, SystemCode.NOTICE_MESSAGE, param);
	}
	// 0. 공통기능 끝

	// 1. 게임 로비
	// "게임방 설정 변경" 메시지 전송
	public void sendGameOptionChangedMessage(String roomId, GameOptionDTO option) {
//		Map<String, Integer> param = new HashMap<>();
		
		sendSystemMessageToAll(roomId, SystemCode.OPTION_CHANGED, option);
	}
	// "게임시작" 메시지 전송
	public void sendGameStartMessage(String roomId) {
		GameRoom gameRoom = gameRoomMap.get(roomId);
		List<String> playersId = gameRoom.getPlayersId();
		sendSystemMessage(roomId, playersId, SystemCode.GAME_START, null);
	}
	// 1. 게임 로비 끝

	// 2. 게임 진행
	// "대상선택" 메시지 전송
	// "대상선택 종료" 메시지 전송
	// "역할배정" 메시지 전송
	public void sendRoleAsignMessage(String roomId, Map<String, RolePlayer> roleMap) {
		GameRoom gameRoom = gameRoomMap.get(roomId);
		List<String> playersId = gameRoom.getPlayersId();
		System.out.println(roleMap);
		ChannelTopic gameTopic = getGameTopic(roomId);
		for (String target : playersId) {
			SystemMessage systemMessage = new SystemMessage(SystemCode.ROLE_ASIGNED, roomId, target, roleMap.get(target));
			gamePublisher.publish(gameTopic, systemMessage);
		}
	}

	// "낮 페이즈" 메시지 전송
	// "저녁 페이즈" 메시지 전송
	// "밤 페이즈" 메시지 전송
	// "낮 투표 종료" 메시지 전송
	// "저녁 투표 종료" 메시지 전송
	// "추방당함" 메시지 전송
	// "사냥당함" 메시지 전송
	// "투표현황" 메시지 전송
	// "심리분석 시작" 메시지 전송
	// "협박당함" 메시지 전송
	// "히든미션 시작" 메시지 전송
	// "히든미션 성공" 메시지 전송
	// 2. 게임 진행 끝

	// 3. 게임 종료
	// "게임종료" 메시지 전송
	// "게임준비" 메시지 전송
	// 3. 게임 종료 끝

}
