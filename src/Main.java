import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

public class Main {
    public static void main(String[] args) throws Exception {
        Runtime rt = Runtime.instance();

        Profile p = new ProfileImpl();
        p.setParameter(Profile.GUI, "true");

        AgentContainer main = rt.createMainContainer(p);

        AgentController dispatcher = main.createNewAgent(
                "dispatcher",
                DispatcherAgent.class.getName(),
                new Object[]{}
        );
        dispatcher.start();

        int techCount = 3;
        for (int i = 1; i <= techCount; i++) {
            main.createNewAgent(
                    "tech" + i,
                    TechnicianAgent.class.getName(),
                    new Object[]{}
            ).start();
        }

        int clientCount = 8;
        for (int i = 1; i <= clientCount; i++) {
            main.createNewAgent(
                    "client" + i,
                    ClientAgent.class.getName(),
                    new Object[]{"dispatcher", String.valueOf(1200 + i * 250)}
            ).start();
        }
    }
}
