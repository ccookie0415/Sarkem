package com.a702.sarkem.model.game;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.a702.sarkem.model.game.dto.GameOptionDTO;
import com.a702.sarkem.model.player.GameRole;
import com.a702.sarkem.model.player.RolePlayer;
import com.a702.sarkem.service.GameManager;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Setter
@Getter
@Builder
@ToString
@AllArgsConstructor
@Slf4j
public class GameSession {
	
	private static GameManager gameManager;

	public enum PhaseType {
		READY, DAY, TWILIGHT, NIGHT
	}

	// 게임방 현황
	@NonNull
	private final String roomId;
	@NonNull
	private String gameId;
	private List<RolePlayer> players;

	// 게임 옵션
	private int citizenCount;
	private int sarkCount;
	private int policeCount;
	private int doctorCount;
	private int bullyCount;
	private int psychologistCount;
	private int detectiveCount;
	private int meetingTime;

	// 게임 현황
	private int day;
	private PhaseType phase;
	private LocalDateTime startTime;
	private LocalDateTime finishedTime;
	private boolean bHiddenMissionStatus;
	private boolean bHiddenMissionSuccess;
	private int hiddenMissionSuccessCnt;
	private int hiddenMissionCnt;
	private int expulsionVoteCnt; // 추방 투표 수
	private int winTeam; // 0: 진행 중 , 1: 삵 승리 , 2: 시민 승리

	public GameSession(GameManager gameManager, String roomId, String gameId) {
		this.gameManager = gameManager;
		this.roomId = roomId;
		this.gameId = gameId;
		this.players = new ArrayList<>(10);
		this.phase = PhaseType.READY;
		this.citizenCount = 1;
		this.sarkCount = 1;
		this.policeCount = 1;
		this.doctorCount = 1;
		this.bullyCount = 1;
		this.psychologistCount = 1;
		this.meetingTime = 60;
		this.day = 0;
		this.winTeam = 0;
	}
	
	public GameSession(GameManager gameManager, String roomId, String gameId, GameOptionDTO optionDto) {
		this.gameManager = gameManager;
		this.roomId = roomId;
		this.gameId = gameId;
		this.players = new ArrayList<>(10);
		this.phase = PhaseType.READY;
		this.citizenCount = optionDto.getCitizenCount();
		this.sarkCount = optionDto.getSarkCount();
		this.policeCount = optionDto.getPoliceCount();
		this.doctorCount = optionDto.getDoctorCount();
		this.bullyCount = optionDto.getBullyCount();
		this.psychologistCount = optionDto.getPsychologistCount();
		this.meetingTime = optionDto.getMeetingTime();
		this.day = 0;
		this.winTeam = 0;
	}

	public int nextDay() {
		return ++day;
	}

	public int addExpulsionVoteCnt() {
		return ++expulsionVoteCnt;
	}

	/**
	 * 총 역할 수 반환
	 * 
	 * @return 총 역할 수
	 */
	public int getTotalRoleCount() {
		return citizenCount + sarkCount + policeCount + doctorCount + bullyCount + psychologistCount + detectiveCount;
	}

	public int[] getRoles() {
		return new int[] { citizenCount, sarkCount, policeCount, doctorCount, bullyCount, psychologistCount,
				detectiveCount };
	}

	public RolePlayer getPlayer(String playerId) {
		for (RolePlayer p : this.players) {
			if (p.getPlayerId().equals(playerId)) {
				return p;
			}
		}
		return null;
	}
	
	public List<RolePlayer> getPlayers() {
		LocalDateTime tenSecondsBefore = LocalDateTime.now().minusSeconds(10);
		for (Iterator<RolePlayer> itr = players.iterator(); itr.hasNext();) {
			RolePlayer p = itr.next();
			if (p.getLastUpdateTime().isBefore(tenSecondsBefore)) {
				log.debug(p.toString() + " is Removed");
				gameManager.sendLeaveGameMessage(roomId, p.getPlayerId());
				itr.remove();
			}
		}
		return this.players;
	}

	// 살아있는 플레이어만 반환하는 함수
	public List<RolePlayer> getAlivePlayers() {
		List<RolePlayer> alivePlayers = new ArrayList<>();
		for (RolePlayer p : this.getPlayers()) {
			if (p.isAlive()) {
				alivePlayers.add(p);
			}
		}
		return alivePlayers;
	}

	// 해당 롤 플레이어들 반환하는 함수(살아있는 애들만)
	public List<RolePlayer> getRolePlayers(GameRole role) {
		List<RolePlayer> rolePlayers = new ArrayList<>();
		for (RolePlayer p : this.players) {
			if (p.getRole().equals(role)&&p.isAlive()) {
				rolePlayers.add(p);
			}
		}
		return rolePlayers;
	}

	// 해당 롤 플레이어들 아이디 리스트 반환하는 함수
	public List<String> getRolePlayersId(GameRole role) {
		List<String> rolePlayersId = new ArrayList<>();
		for (RolePlayer p : this.players) {
			if (p.getRole().equals(role)&&p.isAlive()) {
				rolePlayersId.add(p.getPlayerId());
			}
		}
		return rolePlayersId;
	}
	
	// 플레이어들 직업 정보 map으로 묶어서 반환하는 함수
	public Map<String, String> getPlayersJob() {
		Map<String, String> jobMap = new HashMap<>();
		for (RolePlayer p : this.players) {
			jobMap.put(p.getPlayerId(), p.getRole().toString());
		}
		return jobMap;
	}

	// 게임 옵션 반환
	public GameOptionDTO getGameOption() {
		GameOptionDTO gameOption = new GameOptionDTO();
		gameOption.setBullyCount(bullyCount);
		gameOption.setCitizenCount(citizenCount);
		gameOption.setDetectiveCount(detectiveCount);
		gameOption.setDoctorCount(doctorCount);
		gameOption.setMeetingTime(meetingTime);
		gameOption.setPoliceCount(policeCount);
		gameOption.setPsychologistCount(psychologistCount);
		gameOption.setSarkCount(sarkCount);
		return gameOption;
	}

	// 현재 옵션으로 설정된 역할을 리스트로 반환
	public List<GameRole> getAllRoles() {
		List<GameRole> roles = new ArrayList<>();
		for (int i = 0; i < this.citizenCount; i++)
			roles.add(GameRole.CITIZEN);
		for (int i = 0; i < this.sarkCount; i++)
			roles.add(GameRole.SARK);
		for (int i = 0; i < this.policeCount; i++)
			roles.add(GameRole.POLICE);
		for (int i = 0; i < this.doctorCount; i++)
			roles.add(GameRole.DOCTOR);
		for (int i = 0; i < this.bullyCount; i++)
			roles.add(GameRole.BULLY);
		for (int i = 0; i < this.psychologistCount; i++)
			roles.add(GameRole.PSYCHO);
		for (int i = 0; i < this.detectiveCount; i++)
			roles.add(GameRole.DETECTIVE);

		return roles;
	}

	public int getMafiaCount() {
		// TODO: 마피아 수 반환
		return 0;
	}
}