package com.github.rtempleton.cdr_storm;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.apache.storm.StormSubmitter;
import org.apache.storm.generated.AlreadyAliveException;
import org.apache.storm.generated.AuthorizationException;
import org.apache.storm.generated.InvalidTopologyException;
import org.apache.storm.spout.RawScheme;
import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.IRichSpout;
import org.apache.storm.topology.TopologyBuilder;

import com.github.rtempleton.cdr_storm.bolts.CallStatOrigTermBolt;
import com.github.rtempleton.cdr_storm.bolts.DimLookupBolt;
import com.github.rtempleton.cdr_storm.bolts.EnrichDomesticCallsBolt;
import com.github.rtempleton.cdr_storm.bolts.EnrichInternationalCallsBolt;
import com.github.rtempleton.cdr_storm.bolts.PDDandEarlyEvents;
import com.github.rtempleton.cdr_storm.bolts.PutHBaseFact3;
import com.github.rtempleton.cdr_storm.bolts.TimeBucketBolt;
import com.github.rtempleton.poncho.SelectFieldsBolt;
import com.github.rtempleton.poncho.SelectFieldsBolt.SelectType;
import com.github.rtempleton.poncho.StormUtils;
import com.github.rtempleton.poncho.io.ParseDelimitedTextBolt;





public class CDRTopology{
	
	private static final Logger Log = Logger.getLogger(CDRTopology.class);
	private static final String thisName = CDRTopology.class.getSimpleName();
	private final Properties topologyConfig;

	
	public CDRTopology(String properties) throws Exception{
		topologyConfig = StormUtils.readProperties(properties);
	}

	
	
	public static void main(String[] args) throws Exception{
		if (args.length<1){
			usage();
		}
		
		CDRTopology topo = new CDRTopology(args[0]);
		topo.submit(topo.compose());
	}
	
	
	
	protected TopologyBuilder compose() throws Exception{
		
		TopologyBuilder builder = new TopologyBuilder();
		
		IRichSpout kafkaSpout = StormUtils.getKafkaSpout(topologyConfig, new RawScheme());
		builder.setSpout("spout", kafkaSpout, 2);
		
		IRichBolt reader = new ParseDelimitedTextBolt(topologyConfig);
		builder.setBolt("reader", reader, 2).localOrShuffleGrouping("spout");
		
		IRichBolt callStat = new CallStatOrigTermBolt(topologyConfig, StormUtils.getOutputfields(reader));
		builder.setBolt("callStat", callStat).localOrShuffleGrouping("reader");
		
		//The timebucket bolt has two output streams: Domestic Calls and International calls
		IRichBolt timebucket = new TimeBucketBolt(topologyConfig, StormUtils.getOutputfields(callStat));
		builder.setBolt("timebucket", timebucket).localOrShuffleGrouping("callStat");
		
		IRichBolt intlBolt = new EnrichInternationalCallsBolt(topologyConfig, StormUtils.getOutputfields(timebucket, TimeBucketBolt.INTERNATIONAL_STREAM));
		builder.setBolt("intlBolt", intlBolt).localOrShuffleGrouping("timebucket", TimeBucketBolt.INTERNATIONAL_STREAM);
		
		IRichBolt domBolt = new EnrichDomesticCallsBolt(topologyConfig, StormUtils.getOutputfields(timebucket, TimeBucketBolt.DOMESTIC_STREAM));
		builder.setBolt("domBolt", domBolt).localOrShuffleGrouping("timebucket", TimeBucketBolt.DOMESTIC_STREAM);
		
		IRichBolt pdd = new PDDandEarlyEvents(topologyConfig, StormUtils.getOutputfields(intlBolt));
		builder.setBolt("pdd", pdd).localOrShuffleGrouping("intlBolt").localOrShuffleGrouping("domBolt");
		
		IRichBolt dimlookup = new DimLookupBolt(topologyConfig, StormUtils.getOutputfields(pdd));
		builder.setBolt("dimlookup", dimlookup).localOrShuffleGrouping("pdd");
		
		//list the fields we want to keep in the order we want them output.
//		List<String> fact2Select = Arrays.asList(new String[]{"geo_id","date_id","time_id","cust_id","vend_id","cust_rel_id","vend_rel_id","route","connect","early_event","call_duration_cust","i_pdd","e_pdd","orig_number","term_number"});
		List<String> fact3Select = Arrays.asList(new String[]{"call_ts", "geo_id", "cust_id","vend_id","cust_rel_id","vend_rel_id","route","connect","early_event","call_duration_cust","i_pdd","e_pdd","orig_number","term_number"});
		
		IRichBolt select = new SelectFieldsBolt(topologyConfig, StormUtils.getOutputfields(dimlookup), SelectType.RETAIN, fact3Select);
		builder.setBolt("select", select).localOrShuffleGrouping("dimlookup");
	
//		IRichBolt logger = new ConsoleLoggerBolt(topologyConfig, StormUtils.getOutputfields(select));
//		builder.setBolt("consoleLogger", logger).localOrShuffleGrouping("select");
		
//		IRichBolt writer = new PutHBaseFact2(topologyConfig, StormUtils.getOutputfields(select));
//		builder.setBolt("writer", writer).localOrShuffleGrouping("select");
		
		IRichBolt writer = new PutHBaseFact3(topologyConfig, StormUtils.getOutputfields(select));
		builder.setBolt("writer", writer).localOrShuffleGrouping("select");
		
		return builder;
		
	}

	protected void submit(TopologyBuilder builder){
//		conf.registerSerialization(PositionReportOp.TollNotification.class);
//		conf.setDebug(true);

		try {
			StormSubmitter.submitTopology(thisName, topologyConfig, builder.createTopology());
		} catch (AlreadyAliveException e) {
			e.printStackTrace();
			System.exit(-1);
		} catch (InvalidTopologyException e) {
			e.printStackTrace();
			System.exit(-1);
		} catch (AuthorizationException e) {
			e.printStackTrace();
			System.exit(-1);	
		}

	}
	
	
	
	private static void usage(){
		Log.error("You must provide a path to a properties file when running LinearRoadTopology to corrently configure this topology.");
		System.exit(-1);
	}

}
