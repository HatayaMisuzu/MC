package com.mccompanion.terminal;
import com.mccompanion.terminal.launcher.*;import com.mccompanion.terminal.runtime.*;
final class SmokeTestService{
 record Result(boolean success,boolean manualRequired,String summary){}
 Result run(MinecraftInstance instance,RuntimeProfile profile){if(instance.loader()!=LoaderType.FABRIC)return new Result(true,true,"LOCAL_ONLY: verify Mod load log and /companion status manually");var state=new ConnectionService().status(profile);if(!state.connected())return new Result(false,false,"No authenticated Fabric Mod handshake");if(state.companions()==0)return new Result(false,true,"Handshake OK; create a test companion with /companion create TerminalTest, then rerun");return new Result(true,true,"Handshake and companion registration verified; behavior command chain is covered by runtimeFabricE2E and requires the interactive Runtime control channel");}
}
