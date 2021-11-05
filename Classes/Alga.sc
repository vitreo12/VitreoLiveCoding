// AlgaLib: SuperCollider implementation of Alga, an interpolating live coding environment.
// Copyright (C) 2020-2021 Francesco Cameli.

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

Alga {
	classvar <schedulers;
	classvar <servers;
	classvar <clocks;
	classvar <parGroups;
	classvar <oldSynthDefsDir;

	*initSynthDefs {
		AlgaStartup.initSynthDefs;
	}

	*initClass {
		servers = IdentityDictionary(1);
		schedulers = IdentityDictionary(1);
		clocks = IdentityDictionary(1);
		parGroups = IdentityDictionary(1);

		//Make sure to reset it
		"SC_SYNTHDEF_PATH".unsetenv;
	}

	*maxIO {
		^AlgaStartup.algaMaxIO;
	}

	*maxIO_ { | value |
		AlgaStartup.algaMaxIO = value
	}

	*setAlgaSynthDefsDir {
		oldSynthDefsDir = "SC_SYNTHDEF_PATH".getenv;
		"SC_SYNTHDEF_PATH".setenv(AlgaStartup.algaSynthDefPath);
	}

	*restoreSynthDefsDir {
		"SC_SYNTHDEF_PATH".setenv(oldSynthDefsDir);
	}

	*newServer { | server |
		server = server ? Server.default;
		servers[server] = server;
	}

	*clearServer { | server |
		if(server != nil, { servers.removeAt(server) });
	}

	*quitServerAndClear { | server, prevServerQuit |
		if(server != nil, {
			if(server.serverRunning, {
				server.quit(onComplete: { prevServerQuit[0] = true });
			}, {
				prevServerQuit[0] = true;
			});
			this.clearServer(server);
		}, {
			prevServerQuit[0] = true;
		});
	}

	*newScheduler { | server, clock, cascadeMode = false |
		schedulers[server] = AlgaScheduler(server, clock, cascadeMode);
	}

	*getScheduler { | server |
		var scheduler = schedulers[server];
		if(scheduler.isNil, { ("No AlgaScheduler initialized for server " ++ server.asString).error });
		^scheduler;
	}

	*clearScheduler { | server |
		var scheduler = schedulers[server];
		if(scheduler != nil, {
			scheduler.clear;
			schedulers.removeAt(server);
		});
	}

	*clearAllSchedulers {
		if(schedulers != nil, {
			schedulers.do({ | scheduler |
				scheduler.clear;
			});
			schedulers.clear;
		});
	}

	*newClock { | server, clock |
		clock = clock ? TempoClock(1, queueSize:16834).permanent_(true);
		clocks[server] = clock;
		^clock
	}

	*clock { | server |
		server = server ? Server.default;
		^clocks[server]
	}

	*addParGroupOnServerTree {
		//ServerActions pass the server as first arg
		var serverTreeParGroupFunc = { | server |
			//If it's an Alga booted server, create a ParGroup at head
			if(servers[server] != nil, {
				parGroups[server] = ParGroup(server.defaultGroup)
			});
		};

		//Add the function to the init of ServerTree
		ServerTree.add(serverTreeParGroupFunc);

		//On Server quit, remove the func
		ServerQuit.add({ ServerTree.remove(serverTreeParGroupFunc) });
	}

	*parGroup { | server |
		server = server ? Server.default;
		^parGroups[server]
	}

	*checkAlgaAudioControl {
		if(\AlgaAudioControl.asClass == nil, {
			"\n************************************************\n".postln;
			"AlgaAugioControl is not installed. Read the following instructions to install it:".warn;
			"\n1) Download the AlgaAudioControl UGen from https://github.com/vitreo12/AlgaAudioControl/releases/tag/v1.0.0".postln;
			"2) Unzip it to your 'Platform.userExtensionDir'".postln;
			"\nThis UGen fixes some synchronization issues that may result in audio glitches for short enveloped sounds.\nAfter installing it, no further action is required: Alga will detect it and use it internally, and this message will not be shown again.\n".postln;
			"************************************************\n".postln;
		});
	}

	*boot { | onBoot, server, algaServerOptions, clock |
		var prevServerQuit = [false]; //pass by reference: use Array
		var envAlgaServerOptions = topEnvironment[\algaServerOptions];

		server = server ? Server.default;
		algaServerOptions = algaServerOptions ? envAlgaServerOptions;
		algaServerOptions = algaServerOptions ? AlgaServerOptions();

		if(algaServerOptions.class != AlgaServerOptions, {
			"Alga: Use an AlgaServerOptions instance as the algaServerOptions argument".error;
			^this;
		});

		//AlgaServerOptions
		server.options.sampleRate = algaServerOptions.sampleRate;
		server.options.blockSize = algaServerOptions.blockSize;
		server.options.memSize = algaServerOptions.memSize;
		server.options.numBuffers = algaServerOptions.numBuffers;
		server.options.numAudioBusChannels = algaServerOptions.numAudioBusChannels;
		server.options.numControlBusChannels = algaServerOptions.numControlBusChannels;
		server.options.maxNodes = algaServerOptions.maxNodes;
		server.options.maxSynthDefs = algaServerOptions.maxSynthDefs;
		server.options.numWireBufs = algaServerOptions.numWireBufs;
		server.options.numInputBusChannels = algaServerOptions.numInputBusChannels;
		server.options.numOutputBusChannels = algaServerOptions.numOutputBusChannels;
		if(algaServerOptions.supernova, { Server.supernova }, { Server.scsynth });
		server.options.threads = algaServerOptions.supernovaThreads;
		server.options.useSystemClock = algaServerOptions.supernovaUseSystemClock;
		server.options.protocol = algaServerOptions.protocol ? \tcp;
		server.latency = algaServerOptions.latency;

		//Check AlgaSynthDef/IO folder exists...
		if(File.exists(AlgaStartup.algaSynthDefIO_numberPath) == false, {
			("Could not retrieve the correct 'AlgaSyntDefs/IO_...' folder. Running 'Alga.initSynthDefs' now...").warn;
			this.initSynthDefs;
		});

		//Add to SynthDescLib in order for SynthDef.add to work
		SynthDescLib.global.addServer(server);

		//Run CmdPeriod
		CmdPeriod.run;

		//Clear scheduler @server if present
		this.clearScheduler(server);

		//Clear server @server if present, also quit it
		this.quitServerAndClear(server, prevServerQuit);

		//Add the server
		this.newServer(server);

		//Add the clock. Creates a new TempoClock if clock is nil
		clock = this.newClock(server, clock);

		//Create an AlgaScheduler @ the server
		this.newScheduler(server, clock);

		//Create ParGroup when the server boots and keep it persistent
		this.addParGroupOnServerTree;

		//Use AlgaSynthDefs as SC_SYNTHDEF_PATH
		this.setAlgaSynthDefsDir;

		//Boot
		AlgaSpinRoutine.waitFor( { prevServerQuit[0] == true }, {
			server.waitForBoot({
				//Alga has booted: it is now safe to reset SC_SYNTHDEF_PATH
				this.restoreSynthDefsDir;

				//Execute onBoot function
				onBoot.value;

				//Check AlgaAudioControl so that it's printed after boot
				this.checkAlgaAudioControl;
			});
		});
	}

	*quit { | onQuit, server |
		server = server ? Server.default;
		if(servers[server] != nil, {
			server.quit(onComplete: {
				this.clearServer(server);
				this.clearScheduler(server);
				onQuit.value;
			});
		});
	}
}
