package cn.zbx1425.mtrsteamloco.sound;

import mtr.MTRClient;
import transitsim.motion.MotionConstants;
import mtr.data.TrainClient;
import mtr.sound.TrainSoundBase;
import mtr.sound.bve.BveTrainSound;
import mtr.sound.bve.BveTrainSoundConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

public class DwellTimeBveTrainSound extends TrainSoundBase {

    private BveTrainSound bveTrainSound;
    private TrainClient train;

    public DwellTimeBveTrainSound(BveTrainSoundConfig config) {
        this.bveTrainSound = new BveTrainSound(config);
    }

    private DwellTimeBveTrainSound() {

    }

    @Override
    public TrainSoundBase createTrainInstance(TrainClient train) {
        DwellTimeBveTrainSound result = new DwellTimeBveTrainSound();
        result.bveTrainSound = (BveTrainSound)this.bveTrainSound.createTrainInstance(train);
        result.train = train;
        return result;
    }

    @Override
    public void playNearestCar(Level level, BlockPos blockPos, int i) {
        bveTrainSound.playNearestCar(level, blockPos, i);
    }

    @Override
    public void playAllCars(Level level, BlockPos blockPos, int i) {
        bveTrainSound.playAllCars(level, blockPos, i);
    }

    public float oldStopTicks = 0;

    private float elapsedDwellTicks = 0;
    private float totalDwellTicks = 0;
    private float lastRenderedTick = 0;
    private boolean closePlayed;

    @Override
    public void playAllCarsDoorOpening(Level world, BlockPos pos, int carIndex) {
        if (!(world instanceof ClientLevel) || train == null) {
            return;
        }

        final float lastFrameDuration = MTRClient.getLastFrameDuration();
        final float ticksElapsed = Minecraft.getInstance().isPaused() || lastRenderedTick == MTRClient.getGameTick() ? 0 : lastFrameDuration;
        lastRenderedTick = MTRClient.getGameTick();
        elapsedDwellTicks += ticksElapsed;
        if (train.justOpening()) {
            elapsedDwellTicks = 0;
            totalDwellTicks = train.getTotalDwellTicks();
            closePlayed = false;
        }

        // The closing chime ends as the doors finish closing, which is when the server says (0.65.30), not by the
        // platform rail's stop time, which a timetable's later departure makes too short: the chime sounded and the
        // doors stayed open. While the server doesn't know (the train held by a dispatcher or for the run ahead), the
        // chime waits until the doors start to close.
        final float doorsCloseLeft = train.getDoorsCloseTicksLeft();
        final float chimeTicks = bveTrainSound.config.soundCfg.doorCloseSoundLength * 20;
        // The chime ends as the doors finish closing; one shorter than the doors take starts as they start to close
        final boolean chimeNow = doorsCloseLeft >= 0 ? doorsCloseLeft <= Math.max(0, chimeTicks - MotionConstants.DOOR_MOVE_TIME) : train.justClosing(1);

        final SoundEvent soundEvent;
        if (train.justOpening() && bveTrainSound.config.soundCfg.doorOpen != null) {
            soundEvent = bveTrainSound.config.soundCfg.doorOpen;
        } else if (bveTrainSound.config.soundCfg.doorClose != null && !closePlayed && chimeNow && train.getDoorValue() > 0) {
            soundEvent = bveTrainSound.config.soundCfg.doorClose;
            closePlayed = true;
        } else {
            soundEvent = null;
        }

        oldStopTicks = elapsedDwellTicks;

        playLocalSound(world, soundEvent, pos);
    }

    private static void playLocalSound(Level world, SoundEvent event, BlockPos pos) {
        if (event == null) {
            return;
        }
        ((ClientLevel) world).playLocalSound(pos, event, SoundSource.BLOCKS, 1, 1, false);
    }
}
