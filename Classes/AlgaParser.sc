// AlgaLib: SuperCollider implementation of Alga, an interpolating live coding environment.
// Copyright (C) 2020-2022 Francesco Cameli.

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

//All parsing functions in one place. These are used in AlgaNode, AlgaPattern and AlgaPatternPlayer.
//The idea is that Classes that want to use this parser must have it as a member variable.
//The instance of the Class is passed as 'obj' and accessed / manipulated in the parsing functions here
AlgaParser {
	var obj;
	var recursiveObjectList;

	/*
	Required vars:

	outsMapping, sampleAccurateFuncs, defPreParsing,
	currentPatternOutNodes, prevPatternOutNodes,
	latestPlayersAtParam, paramContainsAlgaReaderPfunc, players
	*/

	*new { | obj |
		^super.newCopyArgs(obj)
	}

	//Parse an AlgaTemp
	parseAlgaTempParam { | algaTemp, functionSynthDefDict, topAlgaTemp |
		var validAlgaTemp = false;
		var def = algaTemp.def;
		var defDef;

		if(def == nil, {
			"AlgaPattern: AlgaTemp has a nil 'def' argument".error;
			^nil;
		});

		case

		//Symbol
		{ def.isSymbol } {
			defDef = def;
			algaTemp.checkValidSynthDef(defDef); //check \def right away
			if(algaTemp.valid.not, { defDef = nil });
			validAlgaTemp = true;
		}

		//Event
		{ def.isEvent } {
			defDef = def[\def];
			if(defDef == nil, {
				"AlgaPattern: AlgaTemp's 'def' Event does not provide a 'def' entry".error;
				^nil
			});

			case

			//Symbol: check \def right away
			{ defDef.isSymbol } {
				algaTemp.checkValidSynthDef(defDef); //check \def right away
				if(algaTemp.valid.not, { defDef = nil });
			}

			//Substitute \def with the new symbol
			{ defDef.isFunction } {
				var defName = ("alga_" ++ UniqueID.next).asSymbol;

				//AlgaTemp can be sampleAccurate in AlgaPatterns!
				functionSynthDefDict[defName] = [
					AlgaSynthDef.new_inner(
						defName,
						defDef,
						sampleAccurate: algaTemp.sampleAccurate,
						makeFadeEnv: false,
						makePatternDef: false,
						makeOutDef: false
					),
					algaTemp
				];

				defDef = defName;
				def[\def] = defName;
			};

			//Loop around the event entries and use as Stream, substituting entries
			def.keysValuesDo({ | key, entry |
				if(key != \def, {
					var parsedEntry = this.parseParamInner(entry, functionSynthDefDict);
					if(parsedEntry == nil, { ^nil });
					//Finally, replace in place
					def[key] = parsedEntry.algaAsStream;
				});
			});

			validAlgaTemp = true;
		}

		//Function: subsitute \def with the new symbol
		{ def.isFunction } {
			var defName = ("alga_" ++ UniqueID.next).asSymbol;

			//AlgaTemp can be sampleAccurate in AlgaPatterns!
			functionSynthDefDict[defName] = [
				AlgaSynthDef.new_inner(
					defName,
					def,
					sampleAccurate: algaTemp.sampleAccurate,
					makeFadeEnv: false,
					makePatternDef: false,
					makeOutDef: false
				),
				algaTemp
			];

			defDef = defName;
			algaTemp.setDef(defName); //Substitute .def with the Symbol
			validAlgaTemp = true;
		};

		//Check validity
		if(validAlgaTemp.not, {
			("AlgaPattern: AlgaTemp's 'def' argument must either be a Symbol, Event or Function").error;
			^nil
		});

		//Check if actually a symbol now
		if(defDef.class != Symbol, {
			("AlgaPattern: Invalid AlgaTemp's definition: '" ++ defDef.asString ++ "'").error;
			^nil
		});

		//Return the modified algaTemp (in case of Event / Function)
		^algaTemp;
	}

	//Parse a param looking for AlgaTemps and ListPatterns
	parseParamInner { | value, functionSynthDefDict |
		var genericObject = true;

		//Look for AlgaTemp / AlgaReaderPfunc
		case
		{ value.isAlgaTemp } {
			value = this.parseAlgaTempParam(value, functionSynthDefDict);
			genericObject = false;
		}
		{ value.isAlgaReaderPfunc } {
			if(this.isAlgaPattern, { this.assignAlgaReaderPfunc(value) });
			genericObject = false;
		};

		//Any other object: run parsing
		if(genericObject, {
			value = this.parseGenericObjectParam(value, functionSynthDefDict);
		});

		//Returned parsed element
		^value;
	}

	//Parse an entry
	parseParam { | value, functionSynthDefDict |
		//Used in from {}
		var returnBoth = false;
		if(functionSynthDefDict == nil, {
			returnBoth = true;
			functionSynthDefDict = IdentityDictionary();
		});

		//Reset the vars for parsing a pattern parameter
		if((obj.isAlgaPattern).or(obj.isAlgaPatternPlayer), {
			this.resetPatternParsingVars
		});

		//Actual parsing
		value = this.parseParamInner(value, functionSynthDefDict);

		//Used in from {}
		if(returnBoth, {
			^[value, functionSynthDefDict]
		}, {
			^value
		})
	}

	//Parse an object
	parseGenericObjectParam { | object, functionSynthDefDict, validClasses |
		if(object != nil, {
			recursiveObjectList.add(object);
			object.algaParseObject(
				func: { | val |
					if((val != nil).and(recursiveObjectList.includes(val).not), {
						this.parseParamInner(val, functionSynthDefDict);
					});
				},
				replace: true,
				validClasses: validClasses
			);
		});
		^object;
	}

	//Reset vars used in parsing AlgaPattern and AlgaPatternPlayer
	resetPatternParsingVars { | recursiveObjectListOnly = false |
		if((recursiveObjectListOnly.not).and(obj.isAlgaPattern), {
			obj.paramContainsAlgaReaderPfunc = false;
			obj.latestPlayersAtParam         = IdentityDictionary();
		});
		recursiveObjectList                  = IdentitySet(10);
	}

	//Parse a Function \def entry
	parseFunctionDefEntry { | def, functionSynthDefDict |
		//New defName
		var defName = ("alga_" ++ UniqueID.next).asSymbol;

		//sampleAccurate is set according to sampleAccurateFuncs
		functionSynthDefDict[defName] = AlgaSynthDef(
			defName,
			def,
			outsMapping: obj.outsMapping,
			sampleAccurate: obj.sampleAccurateFuncs
		); //This returns an AlgaSynthDefSpec (for _algaPattern and _algaPatternTempOut)

		//Return the Symbol
		^defName
	}

	//Parse the \def entry
	parseDefEntry { | def, functionSynthDefDict |
		//Literal functions are non-UGen graph functins!
		if((def.isFunction).and(def.isLiteralFunction.not), {
			^this.parseFunctionDefEntry(def, functionSynthDefDict)
		}, {
			//Pattern
			if(def != nil, {
				recursiveObjectList.add(def);
				def.algaParseObject(
					func: { | val |
						if((val != nil).and(recursiveObjectList.includes(val).not), {
							this.parseDefEntry(val, functionSynthDefDict);
						});
					},
					replace: true
				);
			});
		});
		^def
	}

	//Parse an entire def
	parseDef { | def |
		var defDef;
		var defFX;
		var defOut;

		//Store [defName] -> AlgaSynthDef
		var functionSynthDefDict = IdentityDictionary();

		//Wrap in an Event if not an Event already
		if(def.isEvent.not, { def = (def: def) });

		//Store the original def (needed for AlgaPatternPlayer)
		obj.defPreParsing = def.deepCopy;

		//Retrieve entries that need specific parsing
		defDef = def[\def];
		defFX  = def[\fx];
		defOut = def[\out];

		//Return nil if no def
		if(defDef == nil, {
			"AlgaPattern: the Event does not provide a 'def' entry".error;
			^nil
		});

		//Parse and replace \def
		recursiveObjectList = IdentitySet(10); //reset for parseDefEntry
		def[\def] = this.parseDefEntry(defDef, functionSynthDefDict);

		//Parse \fx
		recursiveObjectList.clear; //reset for parseFX
		if(defFX != nil, { def[\fx] = this.parseFX(defFX, functionSynthDefDict) });

		//Parse \out (reset currentPatternOutNodes too)
		if(defOut != nil, {
			obj.prevPatternOutNodes = obj.currentPatternOutNodes.copy;
			obj.currentPatternOutNodes = IdentitySet();
			recursiveObjectList.clear; //reset for parseOut
			def[\out] = this.parseOut(defOut)
		});

		//Parse all the other entries looking for AlgaTemps / ListPatterns
		def.keysValuesDo({ | key, value |
			if((key != \fx).and(key != \out), {
				value = this.parseParam(value, functionSynthDefDict);
				this.calculatePlayersConnections(key); //Also removes old ones if needed
				def[key] = value;
			});
		});

		^[def, functionSynthDefDict];
	}

	//Parse a single \fx Event entry
	parseFXEvent { | value, functionSynthDefDict |
		var foundInParam = false;
		var synthDescFx, synthDefFx, controlNamesFx;
		var isFunction = false;
		var def;

		//Find \def
		def = value[\def];
		if(def == nil, {
			("AlgaPattern: no 'def' entry in 'fx': '" ++ value.asString ++ "'").error;
			^nil
		});

		//If it's a Function, send def to server and replace entries.
		if(def.isFunction, {
			var defName = ("alga_" ++ UniqueID.next).asSymbol;

			//The synthDef will be sent later! just use the def and the desc for now
			synthDefFx = AlgaSynthDef.new_inner(
				defName,
				def,
				makeFadeEnv: false,
				makePatternDef: false,
				makeOutDef: false
			);

			//Get the desc
			synthDescFx = synthDefFx.asSynthDesc;

			//Important: NO sampleAccurate (it's only needed for the triggered pattern synths)
			functionSynthDefDict[defName] = synthDefFx;

			//Replace the def: with the symbol
			def = defName;
			value[\def] = defName;

			isFunction = true;
		});

		//Don't support other classes for now
		if(def.isSymbol.not, {
			("AlgaPattern: 'fx' only supports Symbols and Functions as 'def'").error;
			^nil
		});

		//Check that \def is valid
		if(isFunction.not, {
			synthDescFx = SynthDescLib.global.at(def);
		});

		if(synthDescFx == nil, {
			("AlgaPattern: Invalid AlgaSynthDef in 'fx': '" ++ def.asString ++ "'").error;
			^nil;
		});

		if(isFunction.not, {
			synthDefFx = synthDescFx.def;
		});

		if(synthDefFx.class != AlgaSynthDef, {
			("AlgaPattern: Invalid AlgaSynthDef in 'fx': '" ++ def.asString ++"'").error;
			^nil;
		});

		if(synthDefFx.sampleAccurate, {
			("AlgaPattern: AlgaSynthDef in 'fx': '" ++ def.asString ++"' has 'sampleAccurate' on. This causes synchronization errors").warn;
		});

		controlNamesFx = synthDescFx.controls;
		controlNamesFx.do({ | controlName |
			if(controlName.name == \in, {
				foundInParam = true;
				//Pass numChannels / rate of in param to Event
				value[\inNumChannels] = controlName.numChannels;
				value[\inRate] = controlName.rate;
			})
		});

		//Not found the \in parameter
		if(foundInParam.not, {
			("AlgaPattern: Invalid AlgaSynthDef in 'fx': '" ++ def.asString ++ "': It does not provide an 'in' parameter").error;
			^nil;
		});

		//Pass controlNames / numChannels / rate to Event
		value[\controlNames] = controlNamesFx;
		value[\numChannels] = synthDefFx.numChannels;
		value[\rate] = synthDefFx.rate;

		//Pass explicitFree to Event
		value[\explicitFree] = synthDefFx.explicitFree;

		//Reset paramContainsAlgaReaderPfunc, latestPlayers and recursiveObjectList
		this.resetPatternParsingVars;

		//Loop over the event and parse ListPatterns / AlgaTemps. Also use as Stream for the final entry.
		value.keysValuesDo({ | key, entry |
			var parsedEntry = this.parseParamInner(entry, functionSynthDefDict);
			value[key] = parsedEntry.algaAsStream;
		});

		//Check support for AlgaPatternPlayers on \fx
		this.calculatePlayersConnections(\fx);

		//Reset paramContainsAlgaReaderPfunc, latestPlayers and recursiveObjectList again
		this.resetPatternParsingVars;

		^value
	}

	//Parse the \fx key
	parseFX { | value, functionSynthDefDict |
		case

		//Single Event
		{ value.isEvent } {
			^this.parseFXEvent(value, functionSynthDefDict);
		}

		//If individual Symbol, if it's in SynthDescLib, use it as Event.
		//Otherwise, passthrough (like, \none, \dry)
		{ value.isSymbol } {
			if(SynthDescLib.global.at(value) != nil, {
				^this.parseFXEvent((def: value), functionSynthDefDict)
			});
			^value
		}

		//If individual Function, wrap in Event
		{ (value.isFunction).and(value.isLiteralFunction.not) } {
			^this.parseFXEvent((def: value), functionSynthDefDict)
		};

		//Patterns
		if(value != nil, {
			recursiveObjectList.add(value);
			value.algaParseObject(
				func: { | val |
					if((val != nil).and(recursiveObjectList.includes(val).not), {
						this.parseFX(val, functionSynthDefDict)
					});
				},
				replace: false
			)
		});

		^value
	}

	//Parse a single \out AlgaOut entry
	parseOutAlgaOut { | value, alreadyParsed |
		var node = value.nodeOrig;
		var param = value.paramOrig;

		if(param == nil, param = \in);
		if(param.class != Symbol, {
			"AlgaPattern: the 'param' argument in AlgaOut can only be a Symbol. Using 'in'".error;
			param = \in
		});

		//AlgaNode
		if(node.isAlgaNode, {
			if(alreadyParsed[node] == nil, {
				if(node.isAlgaPattern, {
					"AlgaPattern: the 'out' parameter only supports AlgaNodes, not AlgaPatterns".error;
					^nil
				});
				alreadyParsed[node] = true;
				obj.currentPatternOutNodes.add([node, param]);
			});
		}, {
			//Patterns
			if(value != nil, {
				recursiveObjectList.add(value);
				value.algaParseObject(
					func: { | val |
						if((val != nil).and(recursiveObjectList.includes(val).not), {
							this.parseOut(val, alreadyParsed)
						});
					},
					replace: false
				)
			});
		});

		^value.algaAsStream;
	}

	//Parse the \out key
	parseOut { | value, alreadyParsed |
		alreadyParsed = alreadyParsed ? IdentityDictionary();

		case
		{ value.isAlgaNode } {
			if(alreadyParsed[value] == nil, {
				if(value.isAlgaPattern, {
					"AlgaPattern: the 'out' parameter only supports AlgaNodes, not AlgaPatterns".error;
					^nil
				});
				alreadyParsed[value] = true;
				obj.currentPatternOutNodes.add([value, \in]);
			});
			^value
		}
		{ value.isAlgaOut } {
			^this.parseOutAlgaOut(value, alreadyParsed);
		};

		//Patterns
		if(value != nil, {
			recursiveObjectList.add(value);
			value.algaParseObject(
				func: { | val |
					if((val != nil).and(recursiveObjectList.includes(val).not), {
						this.parseOut(val, alreadyParsed);
					});
				},
				replace: false
			);
		});

		^value.algaAsStream;
	}

	//Found an AlgaReaderPfunc
	assignAlgaReaderPfunc { | algaReaderPfunc |
		var latestPlayer = algaReaderPfunc.patternPlayer;
		var params = algaReaderPfunc.params;
		if(latestPlayer != nil, {
			obj.paramContainsAlgaReaderPfunc = true;
			obj.latestPlayersAtParam[latestPlayer] = obj.latestPlayersAtParam[latestPlayer] ? Array.newClear;
			obj.latestPlayersAtParam[latestPlayer] = obj.latestPlayersAtParam[latestPlayer].add(params).flatten;
		});
		^algaReaderPfunc;
	}

	//Remove an AlgaPatternPlayer connection
	removeAlgaPatternPlayerConnectionIfNeeded { | param |
		if(obj.players != nil, {
			var playersAtParam = obj.players[param];
			if(playersAtParam != nil, {
				playersAtParam.keysValuesDo({ | latestPlayer, latestPlayerParams |
					latestPlayer.removeAlgaPatternEntry(this, param)
				});
				obj.players.removeAt(param);
			});
		});
	}

	//Add an AlgaPatternPlayer connection
	addAlgaPatternPlayerConnectionIfNeeded { | param |
		//Add an AlgaPatternPlayer connection
		if((obj.paramContainsAlgaReaderPfunc).and(obj.latestPlayersAtParam.size > 0), {
			obj.latestPlayersAtParam.keysValuesDo({ | latestPlayer, params |
				latestPlayer.addAlgaPatternEntry(this, param);
			});
			obj.players = obj.players ? IdentityDictionary();
			obj.players[param] = obj.latestPlayersAtParam.copy;
		});
	}

	//Remove AlgaPatternPlayers' connections if there were any.
	//They will be re-assigned if parsing allows it.
	calculatePlayersConnections { | param |
		//Remove old connections at param if needed
		this.removeAlgaPatternPlayerConnectionIfNeeded(param);

		//Enstablish new ones if needed
		this.addAlgaPatternPlayerConnectionIfNeeded(param);
	}
}