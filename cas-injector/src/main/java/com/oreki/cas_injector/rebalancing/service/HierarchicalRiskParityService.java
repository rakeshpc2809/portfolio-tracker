package com.oreki.cas_injector.rebalancing.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class HierarchicalRiskParityService {

    private final JdbcTemplate jdbcTemplate;

    public record HrpResult(
        Map<String, Double> weights,
        double[][] corrMatrix,
        List<String> sortedAmfiCodes
    ) {}

    public HrpResult computeHrpWeights(List<String> amfiCodes) {
        int n = amfiCodes.size();
        if (n == 0) return new HrpResult(Collections.emptyMap(), new double[0][0], Collections.emptyList());
        if (n == 1) return new HrpResult(Map.of(amfiCodes.get(0), 1.0), new double[][]{{1.0}}, amfiCodes);

        try {
            // STEP 1: Fetch 60-day log-returns
            Map<String, double[]> returnsMap = new HashMap<>();
            for (String amfi : amfiCodes) {
                String sql = """
                    SELECT nav FROM (
                        SELECT nav, nav_date FROM fund_history
                        WHERE amfi_code = ?
                        ORDER BY nav_date DESC
                        LIMIT 61
                    ) sub ORDER BY nav_date ASC
                    """;
                List<Double> navs = jdbcTemplate.queryForList(sql, Double.class, amfi);
                if (navs.size() < 31) {
                    log.warn("HRP: AMFI {} has insufficient data ({} points). Falling back to equal weights.", amfi, navs.size());
                    return new HrpResult(equalWeights(amfiCodes), new double[0][0], Collections.emptyList());
                }
                double[] returns = new double[navs.size() - 1];
                for (int i = 0; i < returns.length; i++) {
                    returns[i] = Math.log(navs.get(i + 1) / navs.get(i));
                }
                returnsMap.put(amfi, returns);
            }

            // STEP 2: Covariance and Correlation matrices
            int minLen = returnsMap.values().stream().mapToInt(r -> r.length).min().orElse(0);
            double[][] covMatrix = new double[n][n];
            double[][] corrMatrix = new double[n][n];
            double[] means = new double[n];
            double[] stds = new double[n];

            for (int i = 0; i < n; i++) {
                double[] ri = returnsMap.get(amfiCodes.get(i));
                double sum = 0;
                for (int k = 0; k < minLen; k++) sum += ri[ri.length - minLen + k];
                means[i] = sum / minLen;
            }

            for (int i = 0; i < n; i++) {
                double[] ri = returnsMap.get(amfiCodes.get(i));
                double varSum = 0;
                for (int k = 0; k < minLen; k++) {
                    double diff = ri[ri.length - minLen + k] - means[i];
                    varSum += diff * diff;
                }
                covMatrix[i][i] = varSum / (minLen - 1);
                stds[i] = Math.sqrt(covMatrix[i][i]);
            }

            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double[] ri = returnsMap.get(amfiCodes.get(i));
                    double[] rj = returnsMap.get(amfiCodes.get(j));
                    double covSum = 0;
                    for (int k = 0; k < minLen; k++) {
                        covSum += (ri[ri.length - minLen + k] - means[i]) * (rj[rj.length - minLen + k] - means[j]);
                    }
                    double cov = covSum / (minLen - 1);
                    covMatrix[i][j] = covMatrix[j][i] = cov;
                    corrMatrix[i][j] = corrMatrix[j][i] = (stds[i] > 0 && stds[j] > 0) ? cov / (stds[i] * stds[j]) : 0;
                }
                corrMatrix[i][i] = 1.0;
            }

            // STEP 3: Correlation distance matrix
            double[][] distMatrix = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    distMatrix[i][j] = Math.sqrt(0.5 * (1.0 - corrMatrix[i][j]));
                }
            }

            // STEP 4: Clustering (Average Linkage - UPGMA)
            List<List<Integer>> clusters = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                List<Integer> c = new ArrayList<>();
                c.add(i);
                clusters.add(c);
            }

            while (clusters.size() > 1) {
                double minDev = Double.MAX_VALUE;
                int imin = -1, jmin = -1;
                for (int i = 0; i < clusters.size(); i++) {
                    for (int j = i + 1; j < clusters.size(); j++) {
                        double dist = clusterDist(clusters.get(i), clusters.get(j), distMatrix);
                        if (dist < minDev) {
                            minDev = dist;
                            imin = i; jmin = j;
                        }
                    }
                }
                List<Integer> merged = new ArrayList<>(clusters.get(imin));
                merged.addAll(clusters.get(jmin));
                clusters.remove(jmin);
                clusters.set(imin, merged);
            }

            // STEP 5: Quasi-diagonalization
            List<Integer> sortedIndices = clusters.get(0);

            // STEP 6: Recursive Bisection
            Map<Integer, Double> weightsIdx = new HashMap<>();
            recursiveBisect(sortedIndices, covMatrix, 1.0, weightsIdx);

            Map<String, Double> weights = new HashMap<>();
            for (int i = 0; i < n; i++) {
                weights.put(amfiCodes.get(i), weightsIdx.get(i));
            }
            
            // ─── REORDER MATRIX ───
            // The frontend needs the matrix to match the labels (sortedAmfiCodes)
            double[][] reorderedMatrix = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    reorderedMatrix[i][j] = corrMatrix[sortedIndices.get(i)][sortedIndices.get(j)];
                }
            }

            List<String> sortedAmfi = sortedIndices.stream()
                .map(amfiCodes::get)
                .toList();

            return new HrpResult(weights, reorderedMatrix, sortedAmfi);

        } catch (Exception e) {
            log.error("HRP Weights computation failed: {}", e.getMessage(), e);
            return new HrpResult(equalWeights(amfiCodes), new double[0][0], Collections.emptyList());
        }
    }

    private double clusterDist(List<Integer> c1, List<Integer> c2, double[][] distMatrix) {
        // Average linkage (UPGMA)
        double sum = 0;
        for (int i : c1) {
            for (int j : c2) {
                sum += distMatrix[i][j];
            }
        }
        return sum / (c1.size() * c2.size());
    }

    private void recursiveBisect(List<Integer> indices, double[][] covMatrix, double weight, Map<Integer, Double> weights) {
        if (indices.size() == 1) {
            weights.put(indices.get(0), weight);
            return;
        }

        int mid = indices.size() / 2;
        List<Integer> left = indices.subList(0, mid);
        List<Integer> right = indices.subList(mid, indices.size());

        double varLeft = clusterVar(left, covMatrix);
        double varRight = clusterVar(right, covMatrix);
        double alpha = 1.0 - varLeft / (varLeft + varRight);

        recursiveBisect(left, covMatrix, weight * alpha, weights);
        recursiveBisect(right, covMatrix, weight * (1.0 - alpha), weights);
    }

    private double clusterVar(List<Integer> indices, double[][] covMatrix) {
        double[][] subCov = new double[indices.size()][indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            for (int j = 0; j < indices.size(); j++) {
                subCov[i][j] = covMatrix[indices.get(i)][indices.get(j)];
            }
        }
        // Inverse-variance weight
        double[] ivw = new double[indices.size()];
        double sumIvw = 0;
        for (int i = 0; i < indices.size(); i++) {
            ivw[i] = 1.0 / Math.max(subCov[i][i], 1e-10);
            sumIvw += ivw[i];
        }
        double var = 0;
        for (int i = 0; i < indices.size(); i++) {
            for (int j = 0; j < indices.size(); j++) {
                var += (ivw[i] / sumIvw) * (ivw[j] / sumIvw) * subCov[i][j];
            }
        }
        return var;
    }

    private Map<String, Double> equalWeights(List<String> codes) {
        Map<String, Double> map = new HashMap<>();
        double w = codes.isEmpty() ? 0 : 1.0 / codes.size();
        for (String c : codes) map.put(c, w);
        return map;
    }

    public List<String> computeHercConcentrationSignal(Map<String, Double> hrpWeights, Map<String, Double> actualWeights) {
        List<String> overCon = new ArrayList<>();
        actualWeights.forEach((amfi, actual) -> {
            double hrp = hrpWeights.getOrDefault(amfi, 0.0);
            if (hrp > 0 && actual > hrp * 1.20) {
                overCon.add(amfi);
            }
        });
        return overCon;
    }
}
