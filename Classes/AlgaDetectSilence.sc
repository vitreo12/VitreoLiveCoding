// AlgaLib: SuperCollider implementation of the Alga live coding language
// Copyright (C) 2020-2021 Francesco Cameli

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

AlgaDetectSilence {
	*ar { arg in = 0.0, amp = 0.0001, time = 0.1, doneAction = 2;
		^(DetectSilence.ar(Impulse.ar(0) + in, amp, time, doneAction))
	}

	*kr { arg in = 0.0, amp = 0.0001, time = 0.1, doneAction = 0;
		^(DetectSilence.kr(Impulse.ar(0) + in, amp, time, doneAction))
	}

}