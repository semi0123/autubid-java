package kr.co.emforce.wonderbox.service.impl;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;
import javax.inject.Inject;

import org.apache.log4j.Logger;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.co.emforce.wonderbox.dao.collector.AutoBidDao;
import kr.co.emforce.wonderbox.model.collector.BidFavoriteKeyword;
import kr.co.emforce.wonderbox.module.IProcess;
import kr.co.emforce.wonderbox.service.CpaService;
import kr.co.emforce.wonderbox.util.TimePositionMaker;

@Service
public class CpaServiceImpl implements CpaService {

	private static final Logger log = Logger.getLogger(CpaServiceImpl.class);

	@Inject
	AutoBidDao autoBidDao;

	@Resource(name = "anStatsDNS")
	private String anStatsDNS;

	@Override
	public void runBidModule() {
		Map<String, Object> inputMap = new HashMap<String, Object>();

		try {
			inputMap.put("timePosition", TimePositionMaker.makeTimePosition());
			inputMap.put("bid_status", "CpaActive");
			List<BidFavoriteKeyword> activeBfkList = autoBidDao.selectResvInactCpaKeywordList(inputMap);
			activeBfkList.addAll(autoBidDao.selectResvActKeywordList(inputMap));
			activeBfkList.addAll(autoBidDao.selectResvActCur0KeywordList(inputMap));

			RestTemplate restTemplate = new RestTemplate();
			
			Map<String, Object> todayCpa = null;

			List<String> args = new ArrayList<String>();

			for (BidFavoriteKeyword bfk : activeBfkList) {
				todayCpa = (Map<String, Object>) restTemplate.getForObject(anStatsDNS + "/cpa/today/?kwd_id=" + bfk.getKwd_id(), Map.class).get("data");

				args.clear();
				args.add(bfk.getAdv_id());
				args.add(bfk.getNa_account_ser());
				args.add(bfk.getKwd_id());
				args.add(String.valueOf(bfk.getRec_clk_rnk()));
				args.add(String.valueOf(bfk.getRec_clk_at()));
				args.add(bfk.getTarget());
				args.add(todayCpa.get("today_cost").toString());
				args.add(todayCpa.get("today_conv").toString());
				args.add(String.valueOf(bfk.getGoal_cpa_amt()));
				args.add(String.valueOf(bfk.getMax_bid_amt()));
				args.add(bfk.getEmergency_status());
				args.add(String.valueOf(autoBidDao.selectOneBidFavoriteKeyword(bfk.getKwd_id()).getCur_cpa_amt()));
				args.add(bfk.getIs_resv());

				log.info("IProcess.MODULES_DIR => " + IProcess.MODULES_DIR);
				log.info("IProcess.AUTO_BID_WORKER => " + IProcess.CPA_AUTO_BID_WORKER);
				log.info("args => ");

				int cnt = 0;
				for (String temp : args) {
					log.info("[" + cnt + "] " + temp);
					cnt++;
				}
				runModule(IProcess.MODULES_DIR, IProcess.CPA_AUTO_BID_WORKER, args);
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void runModule(String modPath, String modName, List<String> arguments) throws Exception {
		arguments.add(0, modName);

		ProcessBuilder pb = new ProcessBuilder(arguments);
		pb.directory(new File(modPath));
		pb.start();
	}
	
	@Override
	public void runAllKeyword() {
		List<BidFavoriteKeyword> allBidFavoriteKeyword = autoBidDao.selectAllBidFavoriteKeywords();
		RestTemplate restTemplate = new RestTemplate();
		Map<String, Object> responseData = null;
		int todayCost = 0;
		int todayConv = 0;
		int twoDayCost = 0;
		int twoDayConv = 0;
		int totalCost = 0;
		int totalConv = 0;
		int calculatedCpa = 0;
		
		String body = null;
		ObjectMapper objMapper = new ObjectMapper();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(new MediaType("application", "json", Charset.forName("UTF-8")));
		Map<String, Object> requestBody = new HashMap<String, Object>();
		
		for (BidFavoriteKeyword bfk : allBidFavoriteKeyword){
			try{
				// 오늘 CPA
				responseData = (Map<String, Object>) restTemplate.getForObject(anStatsDNS + "/cpa/today/?kwd_id=" + bfk.getKwd_id(), Map.class).get("data");
				todayCost = Integer.parseInt(responseData.get("today_cost").toString());
				todayConv = Integer.parseInt(responseData.get("today_conv").toString());
				
				// 어제 그제 CPA
				responseData = (Map<String, Object>) restTemplate.getForObject(anStatsDNS + "/cpa/history/2days/?kwd_id=" + bfk.getKwd_id(), Map.class).get("data");
				twoDayCost = Integer.parseInt(responseData.get("cost").toString());
				twoDayConv = Integer.parseInt(responseData.get("conv").toString());
				
				totalCost = todayCost + twoDayCost;
				totalConv = todayConv + twoDayConv;
				calculatedCpa = totalConv == 0 ? 0 : totalCost / totalConv;
				
				requestBody.clear();
				requestBody.put("kwd_id", bfk.getKwd_id());
				requestBody.put("kwd_nm", bfk.getKwd_nm());
				requestBody.put("cpa", calculatedCpa);
				body = objMapper.writeValueAsString(requestBody);
				responseData = restTemplate.postForEntity(anStatsDNS + "/cpa/history/", new HttpEntity(body, headers), Map.class).getBody();
				if( responseData.get("success").equals(Boolean.TRUE) ){
					autoBidDao.updateCurCpaAmtOneBidFavoriteKeyword(requestBody);
					log.info("■■■■■■■■■■■■■■■■■ cpa run all keyword success");
					log.info("kwd_id : " + bfk.getKwd_id() + " / kwd_nm : " + bfk.getKwd_nm() + " / cpa : " + calculatedCpa);
					log.info("■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■");
				}else{
					throw new Exception("POST " + anStatsDNS + "/cpa/history/ failed");
				}
				
			}catch(Exception e){
				log.error("■■■■■■■■■■■■■■■■■ cpa run all keyword error");
				log.error("kwd_id : " + bfk.getKwd_id() + " / kwd_nm : " + bfk.getKwd_nm());
//				e.printStackTrace();
				log.error("■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■");
			}
			
		}
	}

}
