package client.main;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import Network.ClientNetworkController;
import Network.INetworkController;
import Network.RegisterPlayerHalfMapException;
import Network.RegistrationException;
import Network.SendPlayerMovementExcpetion;
import PathfinderAlgorithm.MovementException;
import View.CLIGameView;
import clientGame.EGameState;
import clientGame.GameController;
import clientGame.GameModel;
import clientGame.NetworkReplyInformationBundle;
import clientMap.ClientHalfMap;

/**
 * represents major GoF facade to coordinate and manage all necessary steps and
 * procedures to initialize, run and terminate a game
 * 
 * @author Malte
 *
 */
public class MainClient {

	private static Logger logger = LoggerFactory.getLogger(MainClient.class);

	private static final int CLIENT_QUERY_DELAY = 400;
	private INetworkController netC;
	private GameController gameC;
	private GameModel clientGameModel;
	NetworkReplyInformationBundle netReplyInfoBundle = new NetworkReplyInformationBundle();

	/**
	 * @param netC
	 */
	public MainClient(INetworkController netC, GameModel gameModel) {
		this.netC = netC;
		this.clientGameModel = gameModel;
		this.gameC = new GameController(gameModel, this.netReplyInfoBundle);
	}

	public static void main(String[] args) {

		String serverBaseUrl = args[1];
		String gameId = args[2];

		logger.info("main process has started with serverBaseUrl: {} and gameId: {}", serverBaseUrl, gameId);

		ClientNetworkController netC = new ClientNetworkController(serverBaseUrl, gameId);
		GameModel gameModel = new GameModel();
		MainClient clientController = new MainClient(netC, gameModel);
		CLIGameView view = new CLIGameView(clientController, gameModel);

		try {
			clientController.initGame();
			logger.info("game has been successfully initialized and is about to start.");
			clientController.play();
		} catch (Exception e) {
			logger.error("game could not start due to error: {}", e);
			clientController.terminate(e.getMessage() + "initGame", EInteractionState.GeneralFailure);
		}

	}

	/**
	 * GoF facade to initialize a game by setting up all necessary procedures:
	 * 
	 * [1] register client
	 * 
	 * [2] register clients half map
	 */
	public void initGame() {
		// [1] register client/player to server
		try {
			clientGameModel.setClientRegistered(this.getNetC().registerPlayer());
			logger.info("client has successfully registered.");
		} catch (RegistrationException e) {
			logger.error("client has not successfully registered with this error {}.", e);
			e.printStackTrace();
		}
		clientControllerBehavior_Sleep(CLIENT_QUERY_DELAY);
		clientControllerBehavior_Wait();
		// [2] register/send ClientHalfMap
		try {
			this.netC.registerPlayerHalfMap(new ClientHalfMap());
			logger.info("client has successfully registered the own half map.");
		} catch (RegisterPlayerHalfMapException e) {
			logger.trace("registration of clients half map has failed due to problem: {}", e);
			e.printStackTrace();
		}
	}

	/**
	 * GoF facade to play a game by necessary procedures:
	 * 
	 * [1] retrieve all infos from server
	 * 
	 * [2] decide and calculate next move (nextStep) based on the fresh infos
	 * (netReplyInfoBundle)
	 * 
	 * [3] wait for permission of server to act again
	 * 
	 * @throws MovementException
	 */
	public void play() throws MovementException {
		clientGameModel.setGameStarted(true);
		logger.info("game has started.");
		while (true) {
			clientControllerBehavior_Wait();
			clientGameModel.setGameMap(this.netReplyInfoBundle.getGameMap());
			logger.debug("data about actual game was updated.");
			messagesbase.messagesfromclient.EMove nextStep = this.gameC.decideNextMovement();
			try {
				this.netC.sendPlayerMovement(nextStep);
				logger.info("client has successfully send own next move: {}", nextStep);
			} catch (SendPlayerMovementExcpetion e) {
				logger.error("client failed to send own next move because of: {}", e);
				e.printStackTrace();
			}
			clientControllerBehavior_Sleep(CLIENT_QUERY_DELAY);

		}
	}

	/**
	 * clientController method for interaction/behavior with server: sleeps for a
	 * certain time
	 * 
	 * @param milliSeconds
	 */
	private void clientControllerBehavior_Sleep(int milliSeconds) {
		try {
			Thread.sleep(milliSeconds);
			logger.info("client has waited for {} ms.", milliSeconds);
		} catch (InterruptedException e) {
			logger.error("clients waiting was interrupted because of {}", e);
			terminate(e.getMessage() + "sleep", EInteractionState.GeneralFailure);
		}
	}

	/**
	 * clientController method for interaction/behavior with server: waits for
	 * certain server reactions
	 * 
	 * @param milliSeconds
	 */
	private void clientControllerBehavior_Wait() {
		// [1] set up all relevant variables
		EGameState gameState = null;
		boolean mustWait = true;
		boolean gameTerminated = false; // muss auf false, true nur zu Testzwecken

		while (mustWait && !gameTerminated) {
			// [2] try to query for actual game state
			try {
				netC.requestGameState(this.netReplyInfoBundle);
				logger.debug("network controller has requested the game state of the server.");
			} catch (Exception e) {
				logger.error("network controller has failed to request the game state of the server.");
				terminate(e.getMessage() + "wait", EInteractionState.ServerFailure);

			}
			// [3] update actual game state
			gameState = this.netReplyInfoBundle.getGameState();
			switch (gameState) {
			case MustWait:
				mustWait = true;
				break;
			case MustAct:
				mustWait = false;
				break;
			case Won:
				mustWait = false;
				gameTerminated = true;
			case Lost:
				mustWait = false;
				gameTerminated = true;
			default:
				logger.error("network controller could not retrieve a appropriate game state.");
			}
			clientControllerBehavior_Sleep(CLIENT_QUERY_DELAY);
		}

		if (gameTerminated) {
			if (gameState.equals(EGameState.Won)) {
				logger.info("game was terminated and player has won.");
				terminate("Player won", EInteractionState.NormalTerminated_WON);
			} else {
				logger.info("game was terminated and player has lost.");
				terminate("Player lost", EInteractionState.NormalTerminated_LOST);
			}
		}

	}

	/**
	 * terminates a game with a description and an interaction state (like:
	 * GeneralFailure, ServerFailure, NormalTerminated_WON...)
	 * 
	 * @param terminateDescription
	 * @param interactionState
	 */
	public void terminate(String terminateDescription, EInteractionState interactionState) {
		logger.info("game was terminated because of: {} and with final interaction state: {}", terminateDescription,
				interactionState);
		clientGameModel.setGameTerminated(true);
		clientGameModel.setGameTerminationDescription(terminateDescription);
		System.exit(0);
	}

	public INetworkController getNetC() {
		return netC;
	}

	/**
	 * toString for testing and debugging purpose
	 */
	public String toString() {
		return "MainClient [netC=" + netC + ", clientGameModel=" + clientGameModel + "]";
	}

}
