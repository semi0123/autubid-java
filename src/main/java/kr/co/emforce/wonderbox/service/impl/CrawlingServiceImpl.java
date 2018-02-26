package kr.co.emforce.wonderbox.service.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.servlet.ServletContext;

import org.apache.commons.lang.StringUtils;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.co.emforce.wonderbox.dao.collector.AutoBidDao;
import kr.co.emforce.wonderbox.model.collector.BidFavoriteKeyword;
import kr.co.emforce.wonderbox.model.collector.BidInstance;
import kr.co.emforce.wonderbox.model.collector.BidMachineMng;
import kr.co.emforce.wonderbox.model.collector.CrawlingResult;
import kr.co.emforce.wonderbox.module.IProcess;
import kr.co.emforce.wonderbox.service.CrawlingService;
import kr.co.emforce.wonderbox.util.HistoryUtil;
import kr.co.emforce.wonderbox.util.JsonToClassConverter;
import kr.co.emforce.wonderbox.util.RestTemplateUtil;
import kr.co.emforce.wonderbox.util.TimePositionMaker;



@Service
public class CrawlingServiceImpl implements CrawlingService{
	
	private static final Logger log = Logger.getLogger(CrawlingServiceImpl.class);
	
	@Inject
	ServletContext ctx;
	
	@Inject
	AutoBidDao autoBidDao;
	
	@Resource(name="collectorSqlSessionFactory")
	private SqlSessionFactory collectorSqlSessionFactory;
	
//	@Resource(name="statsSqlSessionFactory")
//	private SqlSessionFactory statsSqlSessionFactory;
	
	@Autowired
	JavaMailSender mailSender;

	@Resource(name = "anStatsDNS")
	private String anStatsDNS;
	
	@Override
	public List<LinkedHashMap<String, Object>> selectForCrawlingModule(Map<String, Object> inputMap) {
		return autoBidDao.selectCrawlingRankKwdList(inputMap);
	}
	
	@Override
	public void runBidModule(Map<String, Object> requestBody) {
		
		String kwd_nm = requestBody.get("kwd_nm").toString();
		String target = requestBody.get("target").toString();
		String checked_at = requestBody.get("checked_at").toString();
		String emergency_status = requestBody.get("emergency_status").toString();
		String rank_range = requestBody.get("rank_range").toString();
		
		String server_name = requestBody.get("server_name").toString();
		Boolean isTest = server_name.toLowerCase().contains("test"); 
		
		// 통계용
		ArrayList<Map<String,Object>> rnk_list = (ArrayList<Map<String, Object>>) requestBody.get("result_rank");

		Map<Object, CrawlingResult> crawlingMap = null;
		List<BidFavoriteKeyword> activeBfkList = null;
		Map<String, Object> joinSelectMap = null;
		
		Map<String, Object> inputMap = null;
		try{
			crawlingMap = JsonToClassConverter.convertToIdMap((ArrayList<Map<String, Object>>) requestBody.get("result_rank"), "site", CrawlingResult.class);
			activeBfkList = autoBidDao.selectResvInactRankKeywordList(new BidFavoriteKeyword().setKwd_nm(kwd_nm)
																					  .setTarget(target)
																					  .setEmergency_status(emergency_status));
			inputMap = new HashMap<String, Object>();
			inputMap.put("bid_status", "Active");
			inputMap.put("timePosition", TimePositionMaker.makeTimePosition());
			inputMap.put("emergency_status", emergency_status);
			inputMap.put("kwd_nm", kwd_nm);
			inputMap.put("target", target);
			inputMap.put("type", "rank");
			// 키워드 bid_status가 Inactive가 아니면서 , resv_status가 Active이고, 현재 스케쥴시간대가 순위기반인 키워드 목록
			activeBfkList.addAll(autoBidDao.selectResvActKeywordList(inputMap));
			inputMap.put("bid_status", "CpaActive"); 
			activeBfkList.addAll(autoBidDao.selectResvActKeywordList(inputMap));

			// resv_status가 Active이고, 현재 스케쥴시간대가 0이면서, bid_status가 Inactive아닌 키워드 목록
			inputMap.put("bid_status", "Active");
			activeBfkList.addAll(autoBidDao.selectResvActCur0KeywordList(inputMap));
			inputMap.put("bid_status", "OppActive");
			activeBfkList.addAll(autoBidDao.selectResvActCur0KeywordList(inputMap));
			inputMap.put("bid_status", "CpaActive"); 
			activeBfkList.addAll(autoBidDao.selectResvActCur0KeywordList(inputMap));
			inputMap.clear();
			
			Integer rank = null;
			Integer opp_rank = null;
			String is_resv = null;
			
			for(BidFavoriteKeyword bfk : activeBfkList){
				joinSelectMap = new ObjectMapper().convertValue(bfk, Map.class);
				is_resv = joinSelectMap.get("is_resv").toString();
//				log.info(joinSelectMap);
//				log.info("adv_id : " + joinSelectMap.get("adv_id"));
//				log.info("kwd_id : " + joinSelectMap.get("kwd_id"));
//				log.info("kwd_nm : " + kwd_nm);
//				log.info("target : " + target);
//				log.info("na_account_ser : " + joinSelectMap.get("na_account_ser"));
//				log.info("goal_rank : " + joinSelectMap.get("goal_rank"));
//				log.info("rank_range : " + crawlingMap.size());
//				log.info("max_bid_amt : " + joinSelectMap.get("max_bid_amt"));
//				log.info("emergency_status : " + joinSelectMap.get("emergency_status"));
//				log.info("cur_rank 1: " + bfk.getRank());
//				log.info("cur_rank 2: " + joinSelectMap.get("rank"));
				
				log.info("checked_at : " + checked_at);
				String advId = String.valueOf(joinSelectMap.get("adv_id"));
				String customerId = String.valueOf(joinSelectMap.get("na_account_ser"));
				String kwdId = String.valueOf(joinSelectMap.get("kwd_id"));
				Integer before_rank = Integer.valueOf(String.valueOf(joinSelectMap.get("rank")));
				
				Integer rankRange = Integer.valueOf(rank_range);
				String maxBidAmt = String.valueOf(joinSelectMap.get("max_bid_amt"));
				String minBidAmt = String.valueOf(joinSelectMap.get("min_bid_amt"));
//				String emergencyStatus = String.valueOf(joinSelectMap.get("emergency_status"));
				String goalRank = String.valueOf(joinSelectMap.get("goal_rank"));
				
				
				rank= 16;
				try {
					rank = Integer.valueOf(String.valueOf(crawlingMap.get(joinSelectMap.get("site")).getRank()));
					log.info("rank : " + rank);
				}catch(Exception e) {
					log.info("===== : " + e.getMessage());
					if( rankRange == 0 ){
						rank = 0;
					}else{
						rank = rankRange + 1;
					}
				}
				
				
				String bid_type = "rank";
				if( "Active".equals(joinSelectMap.get("bid_status"))){
					bid_type = "rank";
				}else if( "OppActive".equals(joinSelectMap.get("bid_status"))){
					bid_type = "opp";
				}
				
				if( "OppActive".equals(joinSelectMap.get("bid_status")) ){
					opp_rank = 16;
					try{
						opp_rank = crawlingMap.get(String.valueOf(joinSelectMap.get("opp_site"))).getRank();
						Integer tempGoalRank = opp_rank - Integer.parseInt(joinSelectMap.get("opp_gap").toString());
						if( tempGoalRank > rankRange){
							tempGoalRank = rankRange;
						}
						
						if( tempGoalRank < 1 ){
							tempGoalRank = 1;
						}
						
						goalRank = tempGoalRank.toString();
						log.info("goalRank : " + goalRank);
					}catch(Exception e){
						//e.printStackTrace();
						log.info("===== : " + e.getMessage());
						// 경쟁사 이탈 시 목표순위 세팅
						goalRank = joinSelectMap.get("opp_goal_rank").toString().equals("0") ? String.valueOf(rankRange) : joinSelectMap.get("opp_goal_rank").toString();
						log.info("goalRank : " + goalRank);
						// opp_rank = 16; 필요 있나...?
					}
				}
				
				// 랭크 변동 이력
				log.info("TODO Write History :");
				log.info(" | customerId : " + customerId+ " |  kwdId : " + kwdId+ " |  kwd_nm : " + kwd_nm + " | checked_at : " + checked_at + " | emergency_status:" + emergency_status); 
								String write_msg = "현재 순위 : "+before_rank + " > " + rank ;
				String user_id = "시스템 ";
				String type_desc = is_resv.equals("Inactive") ? "자동" : "예약";
				HistoryUtil.writekwdBidHistories(customerId, kwdId, kwd_nm, type_desc, write_msg, user_id, checked_at,emergency_status);
				
				// emergency_status=False_cpa인 경우
				if("False_cpa".equals(emergency_status)){
					if( rankRange <= 0 ){
						goalRank = "0";
					}else if( rankRange == 1 ){
						goalRank = "1";
					}else{
						goalRank = Integer.toString(rankRange - 1);
					}
				}	
				
				// emergency_status = False_cpa && bid_status != CpaActive -> emergency_status = False
				if( !"CpaActive".equals(joinSelectMap.get("bid_status")) && "False_cpa".equals(emergency_status)){
					emergency_status = "False";
				}
												
				// emergency_status != False_cpa일 경우 stats에 입찰성공여부 전달
				if( !"False_cpa".equals(emergency_status) ){
					Map<String, Object> restData = new HashMap<String, Object>();
					restData.put("kwd_id", joinSelectMap.get("kwd_id"));
					restData.put("is_success", Integer.parseInt(goalRank) == rank);
					RestTemplateUtil.exchange(anStatsDNS + "/stats/is/goal/", HttpMethod.POST, restData);
				}
							
				List<String> args = new ArrayList<String>();
				args.add(advId);
				args.add(customerId);
				args.add(kwdId);
				args.add(target);
				args.add(rank.toString());
				args.add(rankRange.toString());
				args.add(goalRank);
				args.add(checked_at);
				
				// 최대 입찰가 : 0일 경우 10만원 처리
				args.add((maxBidAmt.equals("0") || maxBidAmt.equals("null")) ? "100000" : maxBidAmt);
				
				// 최소 입찰가 : 0일 경우 70 처리
				args.add((minBidAmt.equals("0") || minBidAmt.equals("null")) ? "70" : minBidAmt);
				
				args.add(emergency_status);
				args.add(opp_rank == null ? "16" : opp_rank.toString());
				args.add(bid_type);
				args.add(is_resv);
				
				log.info("IProcess.MODULES DIR BEFORE LOG");
				log.info("IProcess.MODULES_DIR => " + IProcess.MODULES_DIR);
				log.info("IProcess.AUTO_BID_WORKER BEFORE LOG");
				log.info("IProcess.AUTO_BID_WORKER => " + IProcess.AUTO_BID_WORKER);
				log.info("args BEFORE LOG");
				log.info("args => ");
				
				int cnt = 0;
				for(String temp : args){
					log.info("["+cnt+"] "+temp);
				  	cnt++;
				}
				
				if( isTest == false ){
					log.info("AUTO_BID_WORKER runModule Before");
					runModule(IProcess.MODULES_DIR, IProcess.AUTO_BID_WORKER, args);
					log.info("AUTO_BID_WORKER runModule END");
				}else{
					log.info("■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■");
					log.info(server_name + " 서버 크롤링 테스트");
					log.info("■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■");
				}
				
				
				if( isTest == false ){
					try {
						log.info("RANK HISTORY START");
						int search_ad_id = autoBidDao.selectSearchAdId(kwd_nm);
						log.info("rank history search_ad_id : " + search_ad_id);
						HistoryUtil.writekwdRankHistories(IProcess.RANK_HISTORY_DIR, kwd_nm, target, checked_at, emergency_status, search_ad_id, rnk_list, server_name);
						log.info("RANK HISTORY END");
						
						List<String> rank_args = new ArrayList<String>();
						rank_args.add(StringUtils.substring(checked_at, 0, 10));
						rank_args.add(String.valueOf(search_ad_id));
						rank_args.add(target);
						
						runModule(IProcess.MODULES_DIR, IProcess.KWD_RANK_HISTORIES_WORKER, rank_args);
						
					}catch(Exception e) {
						log.info("rank history error : " + e.getMessage());
					}
				}
			}
			
		}catch(Exception e){
			e.printStackTrace();
			log.info(e.getMessage());
		}
	}
	
	@Override
	public void sendCrawlingPostJsonString(Map<String, Object> requestBody) {
		log.info(new JSONObject(requestBody).toString());
	}

	@Override
	public void directRelocateProcessNum() throws Exception {
		log.info("(수동) 자동입찰 키워드 프로세스 재배정 시작");
		
		Map<String, Object> inputMap = new HashMap<String, Object>();
		inputMap.put("status", "Active");
		List<BidMachineMng> bmmList = autoBidDao.selectStatusFromBidMachine(inputMap);
		inputMap.clear();
		Integer[] bmmArr = new Integer[bmmList.size()];
		Map<Integer, Integer> processCapacity = new HashMap<Integer, Integer>();
		
		int totalCount = 0;
		Integer numOfMachine = bmmArr.length;
		for(int i=0; i<numOfMachine; i++){
			bmmArr[i] = bmmList.get(i).getProcess_num();
			processCapacity.put(bmmArr[i], 0);
		}
		
		List<LinkedHashMap<String, Object>> kwdList = autoBidDao.selectKwdNmAndTargetFromBidFavoriteKeywords(inputMap);
		
		Integer machineNum = null;
		Random random = new Random();
		Integer curCapacity = null;
		for(Map<String, Object> kwd : kwdList){
			while(true){
				machineNum = bmmArr[random.nextInt(numOfMachine)];
				curCapacity = processCapacity.get(machineNum);
				// 프로세스넘버별 130개까지
				if( kwd.get("process_num") != machineNum && curCapacity < 130){
					kwd.put("process_num", machineNum);
					processCapacity.put(machineNum, curCapacity+1);
					totalCount++;
					break;
				}
			}
		}
		
		// SqlSessionFactory를 이용한 수동 트랜잭션 관리
		SqlSession sqlSession = collectorSqlSessionFactory.openSession(ExecutorType.BATCH, false);
		try{
			for(Map<String, Object> kw : kwdList){
				sqlSession.update(AutoBidDao.class.getName() + ".updateProcessNumFromBidFavoriteKeyword", kw);
			}
			sqlSession.commit();
			log.info("(수동) 자동입찰 키워드 재배정 완료 / 업데이트 된 키워드 수 : " + totalCount);
		}catch(Exception e){
			sqlSession.rollback();
			log.error(e.getMessage());
			log.error("(수동) 자동입찰 키워드 재배정 실패");
			throw e;
		}finally{
			try{ if( sqlSession != null ) sqlSession.close(); }catch(Exception e){ }
		}
	}
	
	public void runModule(String modPath, String modName, List<String> arguments) throws Exception {
	    arguments.add(0, modName);
	
	    ProcessBuilder pb = new ProcessBuilder(arguments);
	    pb.directory(new File(modPath));
	    pb.start();
	}	
	
	@Override
	public void crash(String name) {
		if( autoBidDao.updateCrash(name) == 1 ){
			Map<String, Object> inputMap = new HashMap<String, Object>();
			inputMap.put("name", name);
			StringBuffer content = new StringBuffer();
			content.append("\n\n* 해당 메일은 입찰 솔루션 오류 발생시 자동으로 발송되는 메일입니다.\n\n");
			BidInstance instance = autoBidDao.selectBidInstance(name);
			content.append(instance.getDesc()+"\n\n")
				   .append("name : " + instance.getName() + "\n\n")
				   .append("label : " + instance.getLabel() + "\n\n")
				   .append("ip_v4 : " + instance.getIp_v4() + "\n\n");
			SimpleMailMessage smm = new SimpleMailMessage();
			smm.setFrom("jungyw@emforce.co.kr");
			smm.setTo(new String[] {
					"ahnjaemo@emforce.co.kr", 
					"jungyw@emforce.co.kr", 
					"gusfla09@emforce.co.kr",
					"jamjameun@emforce.co.kr",
					"kimnayoung@emforce.co.kr",
					"yhj@emforce.co.kr"
				});
			smm.setSubject("자동입찰 솔루션 오류");
			smm.setText(content.toString());
			mailSender.send(smm);
			log.error(name + " Crawling Error Send Mail");
		}
	}
	
	@Override
	public int updateReRun(String processNum) {
		if( "All".equals(processNum) ){
			processNum = null;
		}
		return autoBidDao.updateReRun(processNum);
	}
}
