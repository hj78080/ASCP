package org.dongguk.common.business;

import lombok.Getter;
import lombok.Setter;
import org.dongguk.common.app.CommonApp;
import org.optaplanner.core.api.score.Score;
import org.optaplanner.core.api.solver.*;
import org.optaplanner.core.api.solver.change.ProblemChange;
import org.optaplanner.core.impl.score.director.InnerScoreDirector;
import org.optaplanner.core.impl.solver.DefaultSolverFactory;
import org.optaplanner.core.impl.solver.change.DefaultProblemChangeDirector;
import org.optaplanner.persistence.common.api.domain.solution.SolutionFileIO;
import org.dongguk.crewpairing.persistence.FlightCrewPairingXlsxFileIO;
import org.dongguk.crewpairing.persistence.FlightCrewPairingXlsxFileIO.FlightCrewPairingXlsxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;
import java.text.SimpleDateFormat;
import org.dongguk.crewpairing.domain.PairingSolution;
import org.dongguk.crewpairing.domain.Pairing;
import org.dongguk.crewpairing.domain.Flight;
import java.time.temporal.ChronoUnit;

import static java.util.stream.Collectors.toList;

@Getter
@Setter
public final class SolutionBusiness<Solution_, Score_ extends Score<Score_>> implements AutoCloseable {
    public static String getBaseFileName(File file) {
        return getBaseFileName(file.getName());
    }

    public static String getBaseFileName(String name) {
        int indexOfLastDot = name.lastIndexOf('.');
        if (indexOfLastDot > 0) {
            return name.substring(0, indexOfLastDot);
        } else {
            return name;
        }
    }

    private static final Comparator<File> FILE_COMPARATOR = new ProblemFileComparator();
    private static final AtomicLong SOLVER_JOB_ID_COUNTER = new AtomicLong();       // 역할 못 찾음
    private static final Logger LOGGER = LoggerFactory.getLogger(SolutionBusiness.class);

    private final CommonApp<Solution_> app;
    private final DefaultSolverFactory<Solution_> solverFactory;
    private final SolverManager<Solution_, Long> solverManager;
    private final SolutionManager<Solution_, Score_> solutionManager;

    /**
     * 멀티 쓰레드 환경에서 동시성 보장을 위해 AtomicReference 사용하지 않음 -
     * 왜냐하면 자바 -> 파이썬 -> 자바 -> ... 순으로 계속 돌린다. 따라서
     * 여러 개의 최적화 문제를 푸는 것이 아닌 하나의 최적화 문제만 풀 것이므로 동시성 문제를 생각할 필요가 없음
     * 하지만 사용해본다!
     */
    private final AtomicReference<SolverJob<Solution_, Long>> solverJobRef = new AtomicReference<>();
    private final AtomicReference<Solution_> workingSolutionRef = new AtomicReference<>();

    // 우리는 옵션들을 Import할 필요가 없으므로 필요 없음
    // private Set<AbstractSolutionImporter<Solution_>> importers;
    // private Set<AbstractSolutionExporter<Solution_>> exporters;

    private File dataDir;
    private SolutionFileIO<Solution_> solutionFileIO;
    private File inputDataDir;
    private File outputDataDir;


    public SolutionBusiness(CommonApp<Solution_> app, SolverFactory<Solution_> solverFactory) {
        this.app = app;
        this.solverFactory = (DefaultSolverFactory<Solution_>) solverFactory;
        this.solverManager = SolverManager.create(solverFactory);
        this.solutionManager = SolutionManager.create(solverFactory);
    }

    public void updateDataDirs() {
        this.inputDataDir = new File(dataDir, "input");
        if (!inputDataDir.exists()) {
            throw new IllegalStateException(String.format("해당 Path [%s]에 Input Data Directory는 존재하지 않습니다.", inputDataDir.getAbsolutePath()));
        }

        this.outputDataDir = new File(dataDir, "output");
        if (!outputDataDir.exists()) {
            throw new IllegalStateException(String.format("해당 Path [%s]에 Output Data Directory는 존재하지 않습니다.", outputDataDir.getAbsolutePath()));
        }
    }

    public List<File> getInputFileList() {
        return getFileList(inputDataDir, solutionFileIO.getInputFileExtension());
    }

    public List<File> getOutputFileList() {
        return getFileList(outputDataDir, solutionFileIO.getInputFileExtension());
    }

    // 동시성 보장을 윈한 Atomic 객체를 사용했으므로 따르게 get, set 만들기
    public Solution_ getSolution() {
        return workingSolutionRef.get();
    }

    public void setSolution(Solution_ solution) {
        workingSolutionRef.set(solution);
    }

    public Score_ getScore() {
        return solutionManager.update(getSolution());
    }

    public boolean isSolving() {
        SolverJob<Solution_, Long> solverJob = solverJobRef.get();
        return solverJob != null && solverJob.getSolverStatus() == SolverStatus.SOLVING_ACTIVE;
    }

    public boolean isConstraintMatchEnabled() {
        return applyScoreDirector(InnerScoreDirector::isConstraintMatchEnabled);
    }

    // 해당 폴더에 어떤 File이 있는 불러오는 메소드
    private static List<File> getFileList(File dataDir, String extension) {
        try (Stream<Path> paths = Files.walk(dataDir.toPath(), FileVisitOption.FOLLOW_LINKS)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith("." + extension))
                    .map(Path::toFile)
                    .sorted(FILE_COMPARATOR)
                    .collect(toList());
        } catch (IOException e) {
            throw new IllegalStateException("Error while crawling data directory (" + dataDir + ").", e);
        }
    }

    public void doProblemChange(ProblemChange<Solution_> problemChange) {
        SolverJob<Solution_, Long> solverJob = solverJobRef.get();
        if (solverJob != null) {
            solverJob.addProblemChange(problemChange);
        } else {
            acceptScoreDirector(scoreDirector -> {
                DefaultProblemChangeDirector<Solution_> problemChangeDirector =
                        new DefaultProblemChangeDirector<>(scoreDirector);
                problemChangeDirector.doProblemChange(problemChange);
            });
        }
    }

    private ScheduledExecutorService scheduler;
    private long startTime;

    public Solution_ solve(Solution_ problem) {
        // 시작 시간 기록
        startTime = System.currentTimeMillis();

        // 현재 날짜와 시간을 기반으로 파일 이름 생성
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH:mm").format(new Date());
        String logFilePath = "./data/crewpairing/score/dhact_" + timeStamp + "_log.txt";
        
        // 스케줄러 시작
        startLogging(logFilePath);

        SolverJob<Solution_, Long> solverJob = solverManager.solveAndListen(SOLVER_JOB_ID_COUNTER.getAndIncrement(),
                id -> problem, this::setSolution);
        solverJobRef.set(solverJob);
        try {
            return solverJob.getFinalBestSolution();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Solver thread was interrupted.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Solver threw an exception.", e);
        } finally {
            solverJobRef.set(null); // Don't keep references to jobs that have finished solving.
            // 스케줄러 종료
            if (scheduler != null) {
                scheduler.shutdown();
            }
        }
    }

    private void startLogging(String logFilePath) {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            PairingSolution solution = (PairingSolution) getSolution();
            if (solution == null) {
                writeLog(logFilePath, "Solution is null, skipping logging.");
                return;
            }

            // 최신 솔루션의 페어링 리스트를 가져와 로깅
            List<Pairing> pairingList = solution.getPairingList();

            // 데드헤드와 맨데이 구하기
            int deadheadCnt = 0;
            int mandayLen = 0;
            int filteredFlightCount = 0;
            int includedFlightCount = 0;
            int[] arr = solution.calculateMandays();

            for (Pairing pairing : pairingList) {
                List<Flight> pair = pairing.getPair();
                if (!pair.isEmpty()) {
                    String originAirport = pair.get(0).getOriginAirport().getName();

                    // HB1 또는 HB2에서 출발하지 않는 페어링 필터링
                    if (!"HB1".equals(originAirport) && !"HB2".equals(originAirport)) {
                        filteredFlightCount += pair.size();
                        continue; // 필터링된 페어링은 계산에서 제외
                    }

                    includedFlightCount += pair.size(); // 포함된 비행 카운트 증가

                    if (pair.get(0).getOriginAirport() != pair.get(pair.size() - 1).getDestAirport()) {
                        deadheadCnt++; // #of deadheads
                    }

                    if (pair.get(0).getOriginTime() != null && pair.get(pair.size() - 1).getDestTime() != null) {
                        mandayLen += ChronoUnit.DAYS.between(pair.get(0).getOriginTime().toLocalDate(), pair.get(pair.size() - 1).getDestTime().toLocalDate()) + 1;
                    }
                }
            }

            writeLog(logFilePath, "Pairing: " + arr[0] + ",Deadhead Count: " + deadheadCnt + ", Mandays: " + mandayLen
                    + ", Active Legs: " + includedFlightCount + ", Excluded Legs: " + filteredFlightCount
                    + ", DH Excluded Legs: " + arr[1]
                    + ", newPairing: " + (arr[0]-arr[1]) + ", newDeadhead Count: " + (deadheadCnt-arr[3]) + ", newMandays: " + arr[2]
                    + ", newActive Legs: " + (includedFlightCount-arr[1]));

        }, 0, 240, TimeUnit.SECONDS);

        // 100분마다 페어링 데이터를 저장하는 작업
        scheduler.scheduleAtFixedRate(() -> {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd-HHmm");
            String timeStr = dateFormat.format(new Date());
            exportPairingData(timeStr); // export 메서드 호출
        }, 0, 100, TimeUnit.MINUTES);
    }

    // exportPairingData 메서드 정의
    private void exportPairingData(String timeStr) {
        FlightCrewPairingXlsxWriter writer = new FlightCrewPairingXlsxWriter((PairingSolution) getSolution());
        writer.exportPairingData(timeStr);
    }

    private void writeLog(String logFilePath, String message) {
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - startTime;
        long minutes = (elapsedTime / 1000) / 60;
        long seconds = (elapsedTime / 1000) % 60;
        String elapsedTimeFormatted = String.format("%02d:%02d", minutes, seconds);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFilePath, true))) {
            writer.write(elapsedTimeFormatted + " - " + message);
            writer.newLine();
        } catch (IOException e) {
            LOGGER.error("Failed to write log", e);
        }
    }

    public void terminateSolvingEarly() {
        SolverJob<Solution_, Long> solverJob = solverJobRef.get();
        if (solverJob != null) {
            solverJob.terminateEarly();
        }
    }

    @Override
    public void close() {
        terminateSolvingEarly();
        solverManager.close();
    }

    /**
     * OptaPlanner는 Score Director Pattern이라는 패턴을 사용하여 최적화 문제를 해결하는데, InnerScoreDirector는 이 패턴의 핵심 역할을 담당합니다.
     * Score Director Pattern은 최적화 문제의 해결과정에서 현재 상태의 점수(Score)를 계산하고, 이 점수를 최적화하려는 방향으로 개선하는 작업을 수행하는 패턴입니다.
     * InnerScoreDirector는 이러한 Score Director Pattern의 내부적인 구현을 담당하며, 다음과 같은 역할을 합니다:
     * Score 계산: 최적화 문제의 현재 상태에 대한 점수를 계산합니다. 최적화 문제의 해법(Solution)과 해당 문제에 맞는 적합도 평가 기준(Score 객체)에 따라 Score를 계산합니다.
     * Score 변경: Score Director Pattern은 현재 해법을 변경하여 점수를 개선하는 방향으로 진행합니다.
     * InnerScoreDirector는 이러한 해법의 변경 작업을 수행하고, 변경된 해법의 점수를 다시 계산합니다.
     * 최적화 알고리즘과의 연동: InnerScoreDirector는 최적화 알고리즘과 상호 작용하여 최적화 문제를 해결합니다.
     * 알고리즘의 결정과정에 따라 다양한 해법을 탐색하고 개선하는데 사용됩니다.
     * 다중 스레드 환경에서의 동기화: OptaPlanner는 멀티 스레드 환경에서 동시에 최적화 알고리즘을 실행하는데, 이때 InnerScoreDirector는 다중 스레드 환경에서 안전하게 동작하도록 동기화 작업을 수행합니다.
     * InnerScoreDirector는 내부적으로 최적화 문제의 상태를 변경하고 Score를 계산하여 문제를 점진적으로 개선하는 중요한 역할을 수행합니다.
     * 이를 통해 OptaPlanner는 다양한 알고리즘과 휴리스틱을 사용하여 최적 또는 근사적인 해답을 찾습니다.
     */
    private <Result_> Result_ applyScoreDirector(Function<InnerScoreDirector<Solution_, Score_>, Result_> function) {
        try (InnerScoreDirector<Solution_, Score_> scoreDirector = (InnerScoreDirector<Solution_, Score_>) solverFactory.getScoreDirectorFactory()
                .buildScoreDirector(true,
                        true)) {
            scoreDirector.setWorkingSolution(getSolution());
            Result_ result = function.apply(scoreDirector);
            scoreDirector.triggerVariableListeners();
            scoreDirector.calculateScore();
            setSolution(scoreDirector.getWorkingSolution());
            return result;
        }
    }

    public void openSolution(File informationFile) {
        Solution_ solution = solutionFileIO.read(informationFile);
        workingSolutionRef.set(solution);
    }


    public void saveSolution(File file) {
        solutionFileIO.write(getSolution(), file);
        LOGGER.info("Saved: CSV File");
    }

    private void acceptScoreDirector(Consumer<InnerScoreDirector<Solution_, Score_>> consumer) {
        applyScoreDirector(s -> {
            consumer.accept(s);
            return null;
        });
    }
}
