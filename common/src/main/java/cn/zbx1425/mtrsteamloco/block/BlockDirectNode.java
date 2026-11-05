package cn.zbx1425.mtrsteamloco.block;

import mtr.block.BlockNode;
import mtr.data.TransportMode;

/**
 * ANTE's node that set the angle of its rails itself. MTR's own nodes do that now, at any angle to 0.1° and with the brush,
 * so this is only kept under its name for worlds that have it, where it is an ordinary MTR node: removed, the block would
 * be gone from the world and MTR would take away the rails at it. It isn't given out any more.
 */
public class BlockDirectNode extends BlockNode {

	public BlockDirectNode() {
		super(TransportMode.TRAIN);
	}
}
