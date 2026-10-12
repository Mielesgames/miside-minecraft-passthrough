#pragma once

// SkyCraft's SKSE co-save. SKSE gives a plugin one unique ID and one save, load and revert callback, so
// every feature that keeps something in the co-save goes through here: deep creatures (Spawn, record
// 'DEEP') and tamed companions (Companion, record 'CMPN'). The unique ID stays 'SCDP', the one the deep
// creatures registered first, so co-saves written before this still load. A record type a game was
// saved without (an older SkyCraft) simply isn't there: each feature starts empty.
namespace skycraft::CoSave
{
	// At plugin load (SKSEPluginLoad): the callbacks must be registered then.
	void Install();
}
