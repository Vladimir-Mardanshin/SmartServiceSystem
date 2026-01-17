import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;

public class Main {
    public static void main(String[] args) throws Exception {
        Runtime rt = Runtime.instance();

        ProfileImpl mainProfile = new ProfileImpl();
        mainProfile.setParameter(Profile.GUI, "true");
        mainProfile.setParameter(Profile.MAIN_HOST, "localhost");
        mainProfile.setParameter(Profile.MAIN_PORT, "1099");

        AgentContainer main = rt.createMainContainer(mainProfile);

        ProfileImpl p2 = new ProfileImpl();
        p2.setParameter(Profile.MAIN_HOST, "localhost");
        p2.setParameter(Profile.MAIN_PORT, "1099");
        p2.setParameter(Profile.CONTAINER_NAME, "remote-1");

        AgentContainer remote = rt.createAgentContainer(p2);

        main.createNewAgent("dispatcher1", DispatcherAgent.class.getName(), new Object[]{}).start();
        remote.createNewAgent("dispatcher2", DispatcherAgent.class.getName(), new Object[]{}).start();

        int techCount = 3;
        for (int i = 1; i <= techCount; i++) {
            main.createNewAgent("tech" + i, TechnicianAgent.class.getName(), new Object[]{}).start();
        }

        int clientCount = 10;
        for (int i = 1; i <= clientCount; i++) {
            String targetDispatcher = (i % 2 == 0) ? "dispatcher1" : "dispatcher2";
            String delay = String.valueOf(900 + i * 200);
            AgentContainer where = (i % 2 == 0) ? main : remote;

            where.createNewAgent(
                    "client" + i,
                    ClientAgent.class.getName(),
                    new Object[]{targetDispatcher, delay}
            ).start();
        }

        Thread.sleep(10000);
        rt.shutDown();
        System.exit(0);
    }
}
